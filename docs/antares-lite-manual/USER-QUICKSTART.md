# User Quickstart

## Start from an extracted distribution

Use JDK 21 or newer. Run the launcher from a working directory in which you
want the server's `data` workspace. Keep that directory consistent when
reopening existing projects.

Windows stdio:

```powershell
.\bin\antares-lite.bat
```

Linux/macOS stdio:

```sh
./bin/antares-lite
```

Configure a local MCP client to launch this executable with no arguments
(or `stdio`). The process reads one JSON-RPC object per line from stdin and
returns one JSON response per request on stdout. Notifications have no response.

For a server kept running separately, pass `http`:

```powershell
.\bin\antares-lite.bat http
```

```sh
./bin/antares-lite http
```

Connect to `http://127.0.0.1:8080/mcp`. `GET /health` reports readiness.
Only one transport argument is supported.

## Initialize and discover tools

The following JSON-RPC messages work over either transport:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"example","version":"1.0"}}}
```

```json
{"jsonrpc":"2.0","method":"notifications/initialized"}
```

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

Over HTTP, send JSON with `Content-Type: application/json` by `POST /mcp`.
Keep the returned `Mcp-Session-Id` on later requests. Initialization without a
header creates a unique session; a client can also supply a distinct ID during
initialization. Later requests without a valid session fail. Notifications
return HTTP 202 with no body. `DELETE /mcp` closes that session; a closed session
must be initialized again. Responses are JSON; no SSE connection is needed.

`tools/list` is the MCP method listing all 34 endpoints. The similarly named
`list_tools` is a callable platform tool that lists 32 domain definitions.
Use `health` for version and endpoint counts.

## Import and identify an archive

These tool-call examples use placeholders; replace them with your archive
bytes and returned IDs. A file path alone is not an import argument.

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"create_project","arguments":{"fileName":"example.jar","archiveBase64":"<Base64 of the input JAR>"}}}
```

The result is text inside `result.content[].text`; for this tool the text
contains JSON. Inspect JSON-RPC `error` and tool `result.isError` before
parsing it. `health` returns plain text instead of a JSON document.

Record `projectId` and `sha256` from import. Import writes a copy and record,
but does not validate every class. Continue with:

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"index_archive","arguments":{"projectId":"<projectId>"}}}
```

```json
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"archive_summary","arguments":{"projectId":"<projectId>"}}}
```

Compare returned `archiveSha256` with the imported record. Use
`verify_archive_identity` with `projectId` and optional `expectedSha256` to
recompute current stored bytes. Require `matches` and, if supplied,
`expectedMatches`. For existing input, verify current identity before reuse.
Names alone are insufficient. Do not edit files in the server workspace;
import changed bytes as a new project.

## Use the file-based HTTP client

Python 3.11 or newer is needed only for the bundled helper scripts. From the
distribution root, with the HTTP server running:

```sh
python scripts/antares_client.py --list --output tools.json
python scripts/antares_client.py --archive example.jar --evidence-dir evidence --output imported.json
python scripts/antares_client.py --tool method_bytecode --arguments-file method.json --output method-response.json
```

`method.json` contains one UTF-8 JSON **arguments object**, for example:

```json
{"projectId":"<projectId>","className":"example.Guard","methodName":"check","methodDescriptor":"(I)Z"}
```

The client initializes, sends the notification, retains/closes its session and
writes the complete response. Imports compare local bytes, returned hash and
fresh server identity. Use `--expected-sha256` to enforce a known input and
`--endpoint` for another server. Each `--evidence-dir` run creates a distinct
export directory. Verify that directory with `--verify-evidence`; the checks
detect altered records, not semantic correctness or author authenticity.
See [analysis and verification](ANALYSIS-AND-VERIFICATION.md).

## Locate an exact method

Use `list_classes` or `search_classes` to locate the class, then
`class_summary` to obtain `methods[].name` and `methods[].descriptor`.

```json
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"class_summary","arguments":{"projectId":"<projectId>","className":"example.Guard"}}}
```

```json
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"method_bytecode","arguments":{"projectId":"<projectId>","className":"example.Guard","methodName":"check","methodDescriptor":"(I)Z"}}}
```

The class/method/descriptor here illustrate addressing; use your actual
inventory. Overloaded name-only calls fail rather than select the first
overload. Bytecode reports `descriptor`, `requestedDescriptor`,
`methodMatchKind` and `exactMethodMatch`. Source instruction indexes include
positions of omitted metadata nodes; copy returned indexes rather than count
printed lines. Decompiler match flags confirm the parsed declaration's erased
signature, not body equivalence; unresolved signatures explicitly fall back.
See [the tool reference](TOOL-REFERENCE.md).

## Choose the next action

Use Rigel for bytecode/CFG claims, Mira for ranked finder candidates, Vega for
reconstructed source, and Altair for concrete method patches.
[Specialist workflows](SPECIALIST-WORKFLOWS.md) explains their task contracts.
A client without subagents can perform those workflows directly.

Finder limits cap returned candidates rather than scan depth. A truncated page
is incomplete payload even if `scanComplete` is true. Query tools and method
listings have their own bounds; see [the tool reference](TOOL-REFERENCE.md).
Do not infer plaintext, branch truth or maliciousness from heuristic output.

Patching uses [the draft/preview/apply protocol](METHOD-PATCHING.md).
A preview request can finish at preview. Application requires permission for
the concrete change and creates a new output project, preserving the input.

## HTTP configuration

| Variable | Default | Effect |
| --- | --- | --- |
| `ANTARES_LITE_HTTP_HOST` | `127.0.0.1` | Bind host |
| `ANTARES_LITE_HTTP_PORT` | `8080` | Bind port |
| `ANTARES_LITE_ALLOW_REMOTE` | `false` | Permit non-loopback binding |
| `ANTARES_LITE_MAX_HTTP_REQUEST_BYTES` | `134217728` | Request-body limit |

Imported bytes have a separate 128 MiB limit. Base64 and JSON overhead mean
HTTP's body limit can be reached before that archive limit. Remote HTTP has no
built-in authentication; protect it at the deployment boundary.

## Diagnose a failed call

An unknown key, wrong envelope or missing required parameter is a caller error.
Read the current schema and correct that call; do not keep sending the same
request. Use `methodDescriptor`, not `descriptor`, in method-tool arguments.
Domain failures can appear as `isError: true` even when HTTP/JSON-RPC succeeded.
A failed preview must never be applied, and an uncertain apply result needs
reconciliation as described in [method patching](METHOD-PATCHING.md).
