import concurrent.futures
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.request

from antares_client import AntaresClient, EvidenceStore, encode, main, verify_evidence

ROOT = Path(__file__).resolve().parent.parent


class EvidenceTests(unittest.TestCase):
    def test_checksum_and_identity_metadata_detect_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = EvidenceStore(Path(temporary) / "records")
            request = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                "params": {"arguments": {"projectId": "p-example"}}}
            payload = {"projectId": "p-example", "inputSha256": "a" * 64}
            response = {"jsonrpc": "2.0", "id": 1,
                "result": {"content": [{"type": "text", "text": json.dumps(payload)}]}}
            store.record(request, encode(response), "1.2.1")
            self.assertEqual(1, verify_evidence(store.directory)["verifiedRecords"])
            manifest = store.directory / "manifest.json"
            document = json.loads(manifest.read_bytes())
            document["records"][0]["projectId"] = "p-other"
            manifest.write_bytes(encode(document))
            with self.assertRaisesRegex(ValueError, "identity metadata"):
                verify_evidence(store.directory)
            document["records"][0]["projectId"] = "p-example"
            manifest.write_bytes(encode(document))
            (store.directory / "0001-response.json").write_bytes(b"{}")
            with self.assertRaisesRegex(ValueError, "checksum"):
                verify_evidence(store.directory)

    def test_manifest_cannot_reference_files_outside_the_export(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = EvidenceStore(Path(temporary) / "records")
            store.record({"id": 1}, encode({"id": 1}), "test")
            document = store.manifest
            document["records"][0]["requestFile"] = "../outside.json"
            (store.directory / "manifest.json").write_bytes(encode(document))
            with self.assertRaises(ValueError):
                verify_evidence(store.directory)


class ClientIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="antares-client-test-")
        cls.workspace = Path(cls.temporary.name)
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        cls.endpoint = f"http://127.0.0.1:{port}/mcp"
        environment = os.environ.copy()
        environment["ANTARES_LITE_HTTP_PORT"] = str(port)
        environment["ANTARES_LITE_HTTP_HOST"] = "127.0.0.1"
        environment["ANTARES_LITE_ALLOW_REMOTE"] = "false"
        java = Path(environment["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") if environment.get("JAVA_HOME") else shutil.which("java")
        cls.logs = (cls.workspace / "server.log").open("wb")
        cls.server = subprocess.Popen([str(java), "-cp", str(ROOT / "antares-lite-app/build/install/antares-lite/lib/*"),
            "net.evrt.antarelite.app.MainKt", "http"], cwd=cls.workspace, env=environment,
            stdout=cls.logs, stderr=cls.logs, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if cls.server.poll() is not None:
                cls.tearDownClass()
                raise RuntimeError("Verification server exited during startup.")
            try:
                with urllib.request.urlopen(cls.endpoint.replace("/mcp", "/health"), timeout=1):
                    return
            except OSError:
                time.sleep(0.1)
        cls.tearDownClass()
        raise RuntimeError("Verification server did not become ready.")

    @classmethod
    def tearDownClass(cls):
        if cls.server.poll() is None:
            cls.server.terminate()
            cls.server.wait(timeout=10)
        cls.logs.close()
        cls.temporary.cleanup()

    def client(self, evidence=None):
        client = AntaresClient(self.endpoint, evidence)
        self.addCleanup(client.close)
        client.initialize()
        return client

    def test_import_unicode_path_and_preserve_original_responses(self):
        archive = self.workspace / "space \u96ea sample.jar"
        shutil.copyfile(ROOT / "case/snake.jar", archive)
        directory = self.workspace / "evidence"
        client = self.client(directory)
        response = client.import_archive(archive, "d4802826f15cb6dc2d1d73f01b7676779a7f3bde239e723680d8ffebe2cfa4ac")
        self.assertFalse(response["result"]["isError"])
        self.assertEqual(4, verify_evidence(directory)["verifiedRecords"])

    def test_concurrent_clients_have_distinct_sessions(self):
        def exercise(_):
            client = AntaresClient(self.endpoint)
            try:
                client.initialize()
                response = client.rpc("tools/list")
                self.assertEqual(34, len(response["result"]["tools"]))
                return client.session
            finally:
                client.close()
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
            sessions = list(executor.map(exercise, range(3)))
        self.assertEqual(3, len(set(sessions)))

    def test_compiling_finder_clean_mutant_fails_independent_semantic_checks(self):
        from verify_case import verify
        report = verify(ROOT / "case/snake.jar", ROOT / "case/snake-deobf-src", self.endpoint)
        self.assertTrue(report["fixed"]["passed"])
        self.assertTrue(report["brokenRejected"])
        self.assertEqual({"fixed": 0, "broken": 0}, report["finderResidue"])

    def test_argument_file_outputs_full_json_and_errors_remain_actionable(self):
        args = self.workspace / "arguments.json"
        args.write_text(json.dumps({"unexpected": "\u96ea"}), encoding="utf-8")
        client = self.client()
        with self.assertRaisesRegex(RuntimeError, "unexpected"):
            client.call("health", json.loads(args.read_text(encoding="utf-8")))
        args.write_text("{}", encoding="utf-8")
        output = self.workspace / "full-response.json"
        self.assertEqual(0, main(["--endpoint", self.endpoint, "--tool", "health", "--arguments-file", str(args), "--output", str(output)]))
        self.assertIn("registeredEndpoints: 34", json.loads(output.read_text(encoding="utf-8"))["result"]["content"][0]["text"])


if __name__ == "__main__":
    unittest.main()
