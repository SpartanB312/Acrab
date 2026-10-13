# User Quickstart

## Start from an extracted distribution

Use JDK 21 or newer. Run the launcher from a working directory in which you
want the server's `data` workspace. Keep that directory consistent when
reopening existing projects.

Windows stdio:

```powershell
.\bin\acrab.bat
```

Linux/macOS stdio:

```sh
./bin/acrab
```

Configure a local MCP client to launch this executable with no arguments
(or `stdio`). The process reads one JSON-RPC object per line from stdin and
returns one JSON response per request on stdout. Notifications have no response.

For a server kept running separately, pass `http`:

```powershell
.\bin\acrab.bat http
```

```sh
./bin/acrab http
```

Connect to `http://127.0.0.1:8080/mcp`. `GET /health` reports readiness.
Only one transport argument is supported.

## Initialize and discover tools

The server supports per-request protocol `2026-07-28` and legacy protocols
`2025-11-25`, `2025-06-18` and `2025-03-26`. An MCP client
normally handles negotiation and transport headers.

### Initialization-based clients

The following legacy JSON-RPC messages work over either transport:

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
Include `Accept: application/json, text/event-stream`. Initialize without a
`Mcp-Session-Id`; client-chosen session IDs are rejected. Keep the returned
session ID on later requests and send `notifications/initialized` before tool
requests. Include the negotiated `MCP-Protocol-Version` on later requests;
an omitted version header is tolerated for older clients, but a mismatched
one is rejected. Unknown versions in `initialize.params.protocolVersion`
negotiate to a supported legacy version, not an arbitrary echoed value.

Notifications return HTTP 202 with no body. `DELETE /mcp` closes the session.
A missing session returns 400; unknown, expired or closed sessions return 404
and require a new handshake. An optional `GET /mcp` with that session and
`Accept: text/event-stream` opens a heartbeat stream, limited to one per
session. Tool responses arrive on their own POST streams, not the GET stream.

### Per-request clients

For `2026-07-28`, use `server/discover` without an initialization handshake:

```json
{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}
```

Every request carries the same protocol and capability metadata in
`params._meta`. For HTTP, include `MCP-Protocol-Version: 2026-07-28` and
`Mcp-Method` matching the JSON-RPC method. A `tools/call` also requires
`Mcp-Name` matching `params.name`. Header-unsafe names use the MCP UTF-8
Base64 sentinel `=?base64?<encoded value>?=`. Missing or mismatched headers
return 400 with error code `-32020`. Unsupported metadata versions return
400 with `-32022` and `error.data.supported` / `requested`.

Call `tools/list` or `tools/call` directly with this metadata. No session
header is required or returned; supplied legacy session headers are ignored.
Results include `resultType: complete` and server information in `_meta`.
Modern GET and DELETE requests to `/mcp` return 405. Unknown modern RPC
methods return 404 with JSON-RPC code `-32601`.

`tools/list` is the MCP method listing all 56 endpoints. The similarly named
`list_tools` is a callable platform tool that lists 54 domain definitions.
Use `health` for version and endpoint counts.

### HTTP response streams

Discovery and listings return JSON. `tools/call` returns
`Content-Type: text/event-stream`: SSE `event: message` frames carry JSON-RPC
data, ending with the matching `id` and `result` or `error`. The stream then
closes. Read the SSE data as JSON, not as a single JSON document. The old
`2024-11-05` HTTP+SSE endpoint protocol is not supported.

Streams send heartbeat comments. If the call includes `params._meta.progressToken`,
the server also sends `notifications/progress` at request start (0/1) and
completion (1/1). These are request-stage markers, not analysis percentages.
No event IDs, replay or `Last-Event-ID` resumption are provided.

Modern stream closure signals cancellation; legacy clients can send
`notifications/cancelled` with the original `params.requestId` on the same
session. A legacy disconnect alone is not a cancellation signal. Cancellation
and timeouts attempt to interrupt only explicitly allowlisted observational
tools. Libraries may not stop immediately.
Import, indexing, draft, preview, apply and Android artifact creation are not interrupted and may complete
after their response stream closes. Never infer that a write did not happen
from a timeout or disconnect. Shutdown waits for active workers to finish.

## Import and identify an archive

The examples below follow a JAR workflow. DEX/APK/AAB, multidex ZIP and manifest
XML use the same `create_project` envelope with their original format extension.
After identity verification, select Canopus's Android tools instead of JVM
indexing. See the [Android guide](ANDROID.md) for bounded evidence, trusted
converter settings and `import_android_artifact` handoffs over the same MCP connection.

These tool-call examples use the legacy envelope and placeholders; replace
them with your archive bytes and returned IDs. Per-request clients also add
the metadata and HTTP headers described above. A file path alone is not an
import argument.

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
| `ACRAB_HTTP_HOST` | `127.0.0.1` | Bind host |
| `ACRAB_HTTP_PORT` | `8080` | Bind port |
| `ACRAB_ALLOW_REMOTE` | `false` | Permit non-loopback binding |
| `ACRAB_MAX_HTTP_REQUEST_BYTES` | `180005548` | UTF-8 body limit, including Base64 and JSON overhead |
| `ACRAB_HTTP_AUTH_TOKEN` | unset | Require a bearer token on actual requests, including `/health` |
| `ACRAB_HTTP_ALLOWED_HOSTS` | `127.0.0.1,localhost,::1` | Comma-separated accepted Host names, without ports |
| `ACRAB_HTTP_ALLOWED_ORIGINS` | empty | Additional allowed browser origins, e.g. `https://client.example` |
| `ACRAB_HTTP_MAX_CONCURRENT_REQUESTS` | `4` | Tool/RPC workers, 1-256; no waiting work queue |
| `ACRAB_HTTP_MAX_SESSIONS` | `256` | Maximum live legacy sessions |
| `ACRAB_HTTP_SESSION_IDLE_TIMEOUT_MS` | `1800000` | Legacy expiry since the last client request |
| `ACRAB_HTTP_REQUEST_TIMEOUT_MS` | `120000` | Maximum wait for an RPC result |
| `ACRAB_HTTP_READ_TIMEOUT_MS` | `30000` | Maximum request-body read duration |
| `ACRAB_HTTP_SSE_HEARTBEAT_MS` | `15000` | SSE heartbeat interval |
| `ACRAB_HTTP_MAX_SSE_STREAMS` | `32` | Combined POST and legacy GET stream limit |

Imported bytes have a separate 128 MiB limit. The default body limit includes
its Base64 expansion plus 1 MiB of envelope overhead. Choose body and concurrency
limits for available JVM heap; requests are buffered and parsed in memory.
Body-reading admission is bounded to the worker count plus two extra slots.
Oversized bodies return 413, body read timeouts 408, and exhausted capacity
429 with `Retry-After`. Rejected capacity requests start no new tool work.

Only UTF-8 JSON and identity content encoding are accepted (otherwise 415).
The Accept header must allow JSON and SSE (otherwise 406). Malformed requests
return 400. Once an SSE response begins, RPC failures and timeouts are final
JSON-RPC error events on that stream, not a changed HTTP status.

By default, non-browser local clients need no token. If configured, send
`Authorization: Bearer <token>` on all actual requests. Browser origins must
match the request origin or the explicit allowlist; invalid Origin or Host
headers return 403. Allowed CORS preflight requests do not require the token,
but subsequent requests do. Host checks do not trust forwarded headers.

Remote binding requires `ACRAB_ALLOW_REMOTE=true` and a bearer token.
Set the allowed hosts to the names clients actually use, not the wildcard bind
address. Put remote deployments behind a TLS-protected, authenticated boundary;
the bearer-token check is not an OAuth authorization server. Reverse proxies
must preserve accepted Host headers and avoid buffering SSE; explicitly allow
browser origins using HTTPS when TLS terminates at a proxy.

## Diagnose a failed call

An unknown key, wrong envelope or missing required parameter is a caller error.
Read the current schema and correct that call; do not keep sending the same
request. Use `methodDescriptor`, not `descriptor`, in method-tool arguments.
Domain failures can appear as `isError: true` even when HTTP/JSON-RPC succeeded.
A failed preview must never be applied, and an uncertain apply result needs
reconciliation as described in [method patching](METHOD-PATCHING.md).
