# Acrab 1.4 Manual

Acrab analyzes JVM JAR and Android inputs over MCP, supports trusted conversions
and guarded JVM method patching. It leaves source bytes unchanged. Its six specialist roles
organize analysis and review; they are client-side instructions, not separate
servers or tool permissions.

## Read the manual by task

| Task | Document |
| --- | --- |
| Start a server, connect, import and inspect a JAR | [User quickstart](USER-QUICKSTART.md) |
| Look up parameters, result fields, limits and tool effects | [Tool reference](TOOL-REFERENCE.md) |
| Configure specialists, delegate and judge evidence coverage | [Specialist workflows](SPECIALIST-WORKFLOWS.md) |
| Draft, preview, apply or recover a method patch | [Method patching](METHOD-PATCHING.md) |
| Use operand, context, string recovery or comparison capabilities | [Static analysis capabilities](ANALYSIS-AND-VERIFICATION.md) |
| Inspect DEX/APK/AAB/manifests or convert an authorized artifact | [Android analysis and conversion](ANDROID.md) |

The distribution includes this entire manual, [the coordinating skill](../../skills/acrab/SKILL.md),
six specialist skills, seven workflow files, portable intelligence requirements,
agent templates and
[the generated tool catalog](../../catalog/tools-catalog.json). The catalog
contains actual schemas from the bundled server. A repository checkout generates
it during distribution tasks; it is not a maintained source JSON file.
Runtime `tools/list` remains authoritative for the connected server.
Connect an MCP client directly to the server; no helper script is required.

## Capabilities and effects

There are 56 registered endpoints: 54 domain tools and the platform endpoints
`health` and `list_tools`. The registry's `analysisTools` count includes
import, patch and conversion tools; it does not mean all 54 have no state effects.

| Activity | Source archive | Server-side effect |
| --- | --- | --- |
| Import | Unchanged | Stores a copy and project record |
| Index and query | Unchanged | Builds/reuses an in-memory index |
| Bytecode/finder analysis | Unchanged | Reads the imported archive/index |
| Reconstruction | Unchanged | Temporary class files and backend cache |
| Patch draft | Unchanged | Validates operations without preparing output |
| Patch preview | Unchanged | Cached prepared class bytes; idempotency bookkeeping may persist |
| Patch apply | Unchanged | Publishes a new artifact/project; output indexing is a separate postcheck |
| Android inspection | Unchanged | Verifies and parses input without JVM indexing |
| Android artifact creation | Unchanged | Optional trusted process, working tree and separate output artifact |
| Derived artifact import | Unchanged | Copies a project-owned artifact into a new project |

All workflows are static: the server does not run target code, class
initializers, decoders or bootstrap methods. Static string recovery models
supported operations as data. Finder results are advisory
candidates. Decompiled source is reconstruction. The patch verifier's
`asm-static` checks do not prove runtime linking or intended behavior.
Contextual argument/field maps are recorded assumptions. Structural comparison,
detector coverage and static verification have distinct scopes; none establishes
behavioral equivalence.

## Supported deployment

Use JDK 21 or newer. The launcher supports newline-delimited stdio JSON-RPC
and Streamable HTTP at `/mcp`, with JSON discovery/listings and request-scoped
SSE tool responses. `2026-07-28` uses per-request metadata; the supported
2025 protocols retain initialization and session headers. `GET /health`
reports readiness. HTTP defaults to loopback with Host/Origin validation.
Remote binding requires `ACRAB_ALLOW_REMOTE=true`, a bearer token,
allowed hosts and a TLS-protected trust boundary. See
[the quickstart](USER-QUICKSTART.md) for headers, limits and cancellation rules.

The CLI stores projects under `data` relative to its working directory.
Projects persist; most analysis caches and prepared preview bytes do not.
There is no CLI workspace-selection environment variable.

Android signing/deployment, a dashboard, REST compatibility, arbitrary bytecode transformations
and target execution are outside the supported workflow. Archive counts do
not model JVM dependency resolution or multi-release runtime selection.
