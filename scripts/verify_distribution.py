#!/usr/bin/env python3
"""Verify extracted server ZIP/TAR resources, launchers, and the source case rebuild."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def run(command, cwd=None, input_text=None):
    result = subprocess.run(command, cwd=cwd, input=input_text, text=True, encoding="utf-8", errors="replace",
        capture_output=True, timeout=45, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result.stdout


def safe_target(root, name):
    if "\\" in name:
        raise ValueError(f"Nonportable archive entry: {name}")
    destination = (root / name).resolve()
    destination.relative_to(root.resolve())
    return destination


def extract(archive, root):
    root.mkdir(parents=True)
    if archive.suffix == ".zip":
        with zipfile.ZipFile(archive) as package:
            for entry in package.infolist():
                target = safe_target(root, entry.filename)
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(package.read(entry))
    else:
        with tarfile.open(archive) as package:
            for entry in package:
                target = safe_target(root, entry.name)
                if entry.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                elif entry.isfile():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with package.extractfile(entry) as stream:
                        target.write_bytes(stream.read())
                    target.chmod(entry.mode)
                else:
                    raise ValueError(f"Unsupported TAR entry: {entry.name}")
    directories = [path for path in root.iterdir() if path.is_dir()]
    if len(directories) != 1:
        raise ValueError("Expected one distribution root.")
    return directories[0]


def hashes(directory):
    return {file.relative_to(directory).as_posix(): hashlib.sha256(file.read_bytes()).hexdigest()
        for file in directory.rglob("*") if file.is_file()}


def check_schema(node):
    if isinstance(node, dict):
        for name in node.get("required", []):
            property = node.get("properties", {}).get(name)
            if not property or not ("type" in property or "oneOf" in property):
                raise ValueError(f"Incomplete schema for {name}")
        for value in node.values():
            check_schema(value)
    elif isinstance(node, list):
        for value in node:
            check_schema(value)


def verify_server(archive, root):
    distribution = extract(archive, root)
    installed = ROOT / "antares-lite-app/build/install/antares-lite"
    if hashes(distribution) != hashes(installed):
        raise ValueError("Extracted files differ from the installed distribution.")
    for category in ("docs/antares-lite-manual", "skills/antares-lite"):
        if hashes(distribution / category) != hashes(ROOT / category):
            raise ValueError(f"Packaged resources missing or different: {category}")
    for resource in ("scripts/antares_client.py", "scripts/README.md", "LICENSE"):
        if (distribution / resource).read_bytes() != (ROOT / resource).read_bytes():
            raise ValueError(f"Packaged resource differs from source: {resource}")
    messages = [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "health", "arguments": {}}},
    ]
    launcher = distribution / "bin" / ("antares-lite.bat" if os.name == "nt" else "antares-lite")
    if os.name == "nt":
        command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(launcher)]
    else:
        launcher.chmod(0o755)
        command = [str(launcher)]
    output = run(command, cwd=root, input_text="\n".join(json.dumps(message) for message in messages) + "\n")
    responses = [json.loads(line) for line in output.splitlines() if line.strip().startswith("{")]
    if len(responses) != 3 or any("error" in response for response in responses):
        raise ValueError("Extracted launcher did not complete the MCP lifecycle.")
    version = responses[0]["result"]["serverInfo"]["version"]
    if distribution.name != "antares-lite-" + version:
        raise ValueError("Runtime version differs from the archive name.")
    tools = responses[1]["result"]["tools"]
    if len(tools) != 34:
        raise ValueError("Unexpected tool count.")
    for tool in tools:
        check_schema(tool["inputSchema"])
    catalog = json.loads((distribution / "catalog/tools-catalog.json").read_text(encoding="utf-8"))
    if {tool["name"] for tool in catalog["tools"]} != {tool["name"] for tool in tools}:
        raise ValueError("Catalog differs from the live registry.")
    return {"archive": archive.name, "version": version, "tools": len(tools), "verifiedFiles": len(hashes(distribution))}


def build_case(source):
    if os.name == "nt":
        shell = shutil.which("pwsh") or shutil.which("powershell")
        return run([shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(source / "build.ps1")], cwd=source.parent)
    return run(["sh", str(source / "build.sh")], cwd=source.parent)


def verify_case_package(root):
    source = ROOT / "case/snake-deobf-src"
    extracted = extract(ROOT / "case/snake-deobf-src.zip", root / "extracted case with spaces")
    build_case(extracted)
    clean = root / "clean source with spaces"
    shutil.copytree(source, clean, ignore=shutil.ignore_patterns("build", "dist"))
    build_case(clean)
    expected = hashes(clean / "build/classes")
    actual = hashes(extracted / "build/classes")
    if len(actual) != 7 or expected != actual:
        raise ValueError("Clean source package rebuild differs from source-tree class bytes.")
    return {"classFiles": len(actual), "cleanRebuildMatches": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="antares-distribution-") as temporary:
        root = Path(temporary)
        server = []
        for suffix in ("zip", "tar"):
            archive = ROOT / f"antares-lite-app/build/distributions/antares-lite-{args.version}.{suffix}"
            server.append(verify_server(archive, root / suffix))
        case = verify_case_package(root)
        report = {"server": server, "case": case, "passed": True}
        text = json.dumps(report, indent=2) + "\n"
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(text, encoding="utf-8")
        print(text, end="")


if __name__ == "__main__":
    main()
