#!/usr/bin/env python3
"""File-based HTTP MCP client and integrity-checked raw evidence exports (stdlib only)."""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request
import uuid


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def tool_payload(response):
    content = response.get("result", {}).get("content", [])
    if len(content) == 1 and content[0].get("type") == "text":
        try:
            return json.loads(content[0]["text"])
        except (ValueError, KeyError):
            pass
    return {}


class EvidenceStore:
    def __init__(self, directory):
        self.directory = Path(directory).resolve()
        self.directory.mkdir(parents=True, exist_ok=False)
        self.manifest = {"schemaVersion": 1, "records": []}

    def record(self, request, response_bytes, server_version):
        request_bytes = encode(request)
        number = len(self.manifest["records"]) + 1
        request_file = f"{number:04d}-request.json"
        response_file = f"{number:04d}-response.json"
        (self.directory / request_file).write_bytes(request_bytes)
        (self.directory / response_file).write_bytes(response_bytes)
        response = json.loads(response_bytes) if response_bytes else {}
        payload = tool_payload(response)
        if not isinstance(payload, dict):
            payload = {}
        self.manifest["records"].append({
            "requestFile": request_file, "responseFile": response_file,
            "requestSha256": digest(request_bytes), "responseSha256": digest(response_bytes),
            "serverVersion": server_version,
            "projectId": payload.get("projectId", payload.get("sourceProjectId")),
            "inputSha256": payload.get("inputSha256", payload.get("archiveSha256", payload.get("sha256"))),
        })
        temporary = self.directory / "manifest.pending"
        temporary.write_bytes(encode(self.manifest))
        temporary.replace(self.directory / "manifest.json")


def verify_evidence(directory):
    root = Path(directory).resolve()
    manifests = [root / "manifest.json"] if (root / "manifest.json").exists() else sorted(root.glob("*/manifest.json"))
    if not manifests:
        raise ValueError("No evidence manifests found.")
    count = 0
    for manifest_file in manifests:
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        if manifest.get("schemaVersion") != 1:
            raise ValueError("Unsupported evidence schema version.")
        if not manifest.get("records"):
            raise ValueError("An empty evidence manifest proves no recorded calls.")
        for record in manifest["records"]:
            files = {}
            for kind in ("request", "response"):
                path = (manifest_file.parent / record[kind + "File"]).resolve()
                path.relative_to(manifest_file.parent.resolve())
                data = path.read_bytes()
                if digest(data) != record[kind + "Sha256"]:
                    raise ValueError(f"Evidence checksum mismatch: {path.name}")
                files[kind] = json.loads(data) if data else {}
            request, response = files["request"], files["response"]
            if response and response.get("id") != request.get("id"):
                raise ValueError("Response/request ID mismatch.")
            payload = tool_payload(response)
            if isinstance(payload, dict):
                project = payload.get("projectId", payload.get("sourceProjectId"))
                source = payload.get("inputSha256", payload.get("archiveSha256", payload.get("sha256")))
                if project != record.get("projectId") or source != record.get("inputSha256"):
                    raise ValueError("Evidence identity metadata mismatch.")
                arguments = request.get("params", {}).get("arguments", {})
                requested_project = arguments.get("projectId", arguments.get("sourceProjectId"))
                if project and requested_project and requested_project != project:
                    raise ValueError("Evidence response belongs to a different requested project.")
            count += 1
    return {"verifiedRecords": count, "manifestCount": len(manifests), "scope": "record-integrity-and-request-identity"}


class AntaresClient:
    def __init__(self, endpoint, evidence_dir=None, timeout=60):
        self.endpoint = endpoint
        self.timeout = timeout
        self.session = None
        self.request_id = 0
        self.server_version = None
        self.evidence = EvidenceStore(Path(evidence_dir) / uuid.uuid4().hex) if evidence_dir else None

    def rpc(self, method, params=None, notification=False):
        request = {"jsonrpc": "2.0", "method": method}
        if not notification:
            self.request_id += 1
            request["id"] = self.request_id
        if params is not None:
            request["params"] = params
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        message = urllib.request.Request(self.endpoint, encode(request), headers, method="POST")
        try:
            response = urllib.request.urlopen(message, timeout=self.timeout)
        except urllib.error.HTTPError as failure:
            raw = failure.read()
            if self.evidence:
                self.evidence.record(request, raw, self.server_version)
            raise RuntimeError(f"HTTP {failure.code}: {raw.decode('utf-8', errors='replace')}") from failure
        with response:
            raw = response.read()
            self.session = response.headers.get("Mcp-Session-Id", self.session)
        document = json.loads(raw) if raw else {}
        self.server_version = document.get("result", {}).get("serverInfo", {}).get("version", self.server_version)
        if self.evidence:
            self.evidence.record(request, raw, self.server_version)
        if document.get("error"):
            raise RuntimeError(json.dumps(document["error"], ensure_ascii=False))
        if not notification and document.get("id") != request["id"]:
            raise RuntimeError("Mismatched JSON-RPC response ID.")
        return document

    def initialize(self):
        response = self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "antares-file-client", "version": "1"}})
        if not self.session:
            raise RuntimeError("Server did not return Mcp-Session-Id.")
        self.rpc("notifications/initialized", notification=True)
        return response

    def call(self, tool, arguments):
        response = self.rpc("tools/call", {"name": tool, "arguments": arguments})
        if response.get("result", {}).get("isError"):
            raise RuntimeError(json.dumps(response["result"], ensure_ascii=False))
        return response

    def import_archive(self, archive, expected_sha256=None):
        path = Path(archive).resolve(strict=True)
        data = path.read_bytes()
        checksum = digest(data)
        if expected_sha256 and expected_sha256.lower() != checksum:
            raise ValueError("Input file differs from expected SHA-256.")
        response = self.call("create_project", {"fileName": path.name, "archiveBase64": base64.b64encode(data).decode("ascii")})
        project = tool_payload(response)
        if project.get("sha256") != checksum:
            raise RuntimeError("Imported archive identity differs from local bytes.")
        identity = tool_payload(self.call("verify_archive_identity", {"projectId": project["projectId"], "expectedSha256": checksum}))
        if not identity.get("matches") or not identity.get("expectedMatches"):
            raise RuntimeError("Stored archive identity verification failed.")
        return response

    def close(self):
        if self.session:
            request = urllib.request.Request(self.endpoint, headers={"Mcp-Session-Id": self.session}, method="DELETE")
            try:
                with urllib.request.urlopen(request, timeout=self.timeout):
                    pass
            except (OSError, urllib.error.HTTPError):
                pass
            self.session = None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", default="http://127.0.0.1:8080/mcp")
    parser.add_argument("--arguments-file", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--evidence-dir", type=Path)
    parser.add_argument("--expected-sha256")
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--tool")
    actions.add_argument("--archive", type=Path)
    actions.add_argument("--list", action="store_true")
    actions.add_argument("--verify-evidence", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.verify_evidence:
            result = verify_evidence(args.verify_evidence)
        else:
            client = AntaresClient(args.endpoint, args.evidence_dir)
            try:
                client.initialize()
                if args.archive:
                    result = client.import_archive(args.archive, args.expected_sha256)
                elif args.list:
                    result = client.rpc("tools/list")
                else:
                    arguments = json.loads(args.arguments_file.read_text(encoding="utf-8-sig")) if args.arguments_file else {}
                    if not isinstance(arguments, dict):
                        raise ValueError("Arguments file must contain one JSON object.")
                    result = client.call(args.tool, arguments)
            finally:
                client.close()
        output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(output, encoding="utf-8")
        else:
            sys.stdout.buffer.write(output.encode("utf-8"))
        return 0
    except (OSError, ValueError, RuntimeError) as failure:
        print(str(failure), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
