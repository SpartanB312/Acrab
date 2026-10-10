# Antares Lite Helpers

`antares_client.py` is a stdlib-only Python 3.11+ HTTP MCP client. Start the
server separately, then run these from the repository or extracted distribution:

```sh
python scripts/antares_client.py --list --output tools.json
python scripts/antares_client.py --archive example.jar --evidence-dir evidence --output imported.json
python scripts/antares_client.py --tool method_bytecode --arguments-file method.json --output response.json
python scripts/antares_client.py --verify-evidence evidence/<export-directory>
```

Argument files contain one UTF-8 JSON arguments object. `--endpoint` changes
the default `http://127.0.0.1:8080/mcp`. Each command handles initialization,
notification and session closure. Imports check local, returned and current
stored identity; `--expected-sha256` enforces a known input. Tool/protocol
failures return a nonzero exit code with complete diagnostics.

Evidence exports contain original requests/responses and a checksum manifest
under a unique directory. They preserve import bytes. Verification detects
alteration and inconsistent source metadata, not author authenticity or truth
of program claims. See the [manual](../docs/antares-lite-manual/USER-QUICKSTART.md).

These additional scripts require a repository checkout and JDK 21:

| Script | Purpose |
| --- | --- |
| `test_antares_client.py` | Fresh-server integration and evidence regression suite |
| `Verify-Reliability.ps1` | Live known-fixture bytecode, context, finder and decompiler checks |
| `verify_case.py` | Bounded external differential probe and deliberate faulty variant |
| `package_case.py` | Source ZIP with stable metadata, scripts, probe and license |
| `verify_distribution.py` | Clean ZIP/TAR launcher/resource checks and source rebuild parity |

Build `:antares-lite-app:installDist` before client tests and all three
distribution tasks before archive checks. The server distribution includes
only the reusable client and this README; repository-only checks need their
fixture and source trees. No script grants the server a target-execution tool.
