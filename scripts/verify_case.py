#!/usr/bin/env python3
"""External headless differential regression for the exact public snake fixture."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from antares_client import AntaresClient, tool_payload

ROOT = Path(__file__).resolve().parent.parent
FIXTURE_SHA256 = "d4802826f15cb6dc2d1d73f01b7676779a7f3bde239e723680d8ffebe2cfa4ac"


def jdk_tool(name):
    executable = name + (".exe" if os.name == "nt" else "")
    if os.environ.get("JAVA_HOME"):
        return str(Path(os.environ["JAVA_HOME"]) / "bin" / executable)
    resolved = shutil.which(executable)
    if not resolved:
        raise RuntimeError(f"JDK tool not found: {name}; set JAVA_HOME.")
    return resolved


def run(command, expect_success=True):
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    if expect_success and result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result


def build(source, output, broken=False):
    classes = output / "classes"
    classes.mkdir(parents=True)
    sources = source / "src"
    if broken:
        sources = output / "mutated-src"
        shutil.copytree(source / "src", sources)
        file = sources / "snake/GameThread.java"
        text = file.read_text(encoding="utf-8")
        if text.count("counter--;") != 1:
            raise RuntimeError("Cannot identify the exact counter mutation site.")
        file.write_text(text.replace("counter--;", "counter = counter;"), encoding="utf-8")
    run([jdk_tool("javac"), "--release", "21", "-encoding", "UTF-8", "-d", str(classes)] +
        [str(file) for file in sorted(sources.rglob("*.java"))])
    archive = output / "snake.jar"
    run([jdk_tool("jar"), "--create", "--file", str(archive), "--main-class", "snake.Snake", "-C", str(classes), "."])
    return archive


def verify(original, source, endpoint=None):
    original = Path(original).resolve(strict=True)
    if hashlib.sha256(original.read_bytes()).hexdigest() != FIXTURE_SHA256:
        raise ValueError("Runtime verification is limited to the exact published fixture.")
    source = Path(source).resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="antares-case-verification-") as temporary:
        root = Path(temporary)
        fixed = build(source, root / "fixed")
        broken = build(source, root / "broken", broken=True)
        probe = root / "probe"
        probe.mkdir()
        run([jdk_tool("javac"), "--release", "21", "-d", str(probe), str(source / "verification/CaseProbe.java")])
        command = [jdk_tool("java"), "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp", str(probe), "CaseProbe", str(original)]
        fixed_result = json.loads(run(command + [str(fixed)]).stdout)
        broken_result = run(command + [str(broken)], expect_success=False)
        if broken_result.returncode == 0 or "Trim divergence" not in broken_result.stderr:
            raise RuntimeError("The intentionally broken reconstruction was not rejected by the differential probe.")
        report = {"inputSha256": FIXTURE_SHA256, "fixed": fixed_result, "brokenRejected": True,
            "scope": "36-trim-and-16-direction-scenarios-under-explicit-false-guard-context"}
        if endpoint:
            client = AntaresClient(endpoint)
            try:
                client.initialize()
                residue = {}
                for name, archive in (("fixed", fixed), ("broken", broken)):
                    project = tool_payload(client.import_archive(archive))
                    summary = tool_payload(client.call("evidence_summary", {"projectId": project["projectId"], "limit": 1000}))
                    if summary["candidateCount"] != 0 or not summary["scanComplete"] or summary["truncated"]:
                        raise RuntimeError(f"Unexpected finder coverage/residue in {name} variant: {summary}")
                    residue[name] = 0
                report["finderResidue"] = residue
            finally:
                client.close()
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original", type=Path, default=ROOT / "case/snake.jar")
    parser.add_argument("--source-dir", type=Path, default=ROOT / "case/snake-deobf-src")
    parser.add_argument("--endpoint")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = verify(args.original, args.source_dir, args.endpoint)
    text = json.dumps(result, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text, encoding="utf-8")
    print(text, end="")


if __name__ == "__main__":
    main()
