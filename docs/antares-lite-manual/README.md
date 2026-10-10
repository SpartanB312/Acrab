# Antares Lite 1.3.0 Manual

Antares Lite analyzes JVM JAR archives over MCP and supports guarded method
patching. It leaves the source archive unchanged. Its five specialist roles
organize analysis and review; they are client-side instructions, not separate
servers or tool permissions.

## Read the manual by task

| Task | Document |
| --- | --- |
| Start a server, connect, import and inspect a JAR | [User quickstart](USER-QUICKSTART.md) |
| Look up parameters, result fields, limits and tool effects | [Tool reference](TOOL-REFERENCE.md) |
| Configure specialists, delegate and judge evidence coverage | [Specialist workflows](SPECIALIST-WORKFLOWS.md) |
| Draft, preview, apply or recover a method patch | [Method patching](METHOD-PATCHING.md) |
| Trace operands, conditional context, strings and independent checks | [Analysis and verification](ANALYSIS-AND-VERIFICATION.md) |

The distribution includes this entire manual, [the coordinating skill](../../skills/antares-lite/SKILL.md),
five specialist skills, seven workflow files, portable intelligence ratings,
agent templates, the Python HTTP client and
[the generated tool catalog](../../catalog/tools-catalog.json). The catalog
contains actual schemas from the bundled server. A repository checkout generates
it during distribution tasks; it is not a maintained source JSON file.
Runtime `tools/list` remains authoritative for the connected server.

## Capabilities and effects

There are 34 registered endpoints: 32 domain tools and the platform endpoints
`health` and `list_tools`. The registry's `analysisTools` count includes
import and patch tools; it does not mean all 32 have no state effects.

| Activity | Source archive | Server-side effect |
| --- | --- | --- |
| Import | Unchanged | Stores a copy and project record |
| Index and query | Unchanged | Builds/reuses an in-memory index |
| Bytecode/finder analysis | Unchanged | Reads the imported archive/index |
| Reconstruction | Unchanged | Temporary class files and backend cache |
| Patch draft | Unchanged | Validates operations without preparing output |
| Patch preview | Unchanged | Cached prepared class bytes; idempotency bookkeeping may persist |
| Patch apply | Unchanged | Publishes a new artifact/project; output indexing is a separate postcheck |

All workflows are static: the server does not run target code, class
initializers, decoders or bootstrap methods. Static string recovery models
supported operations as data. Finder results are advisory
candidates. Decompiled source is reconstruction. The patch verifier's
`asm-static` checks do not prove runtime linking or intended behavior.
Contextual argument/field maps are recorded assumptions. Compilation, structural
comparison, detector coverage and external fixture behavior tests are separate
checks; none is a substitute for another.

## Supported deployment

Use JDK 21 or newer. The launcher supports newline-delimited stdio JSON-RPC
and HTTP JSON-RPC at `/mcp`. HTTP has session headers and `GET /health`;
it has no SSE stream. It defaults to loopback, and remote binding needs
`ANTARES_LITE_ALLOW_REMOTE=true` plus an external authenticated trust boundary.

The CLI stores projects under `data` relative to its working directory.
Projects persist; most analysis caches and prepared preview bytes do not.
There is no CLI workspace-selection environment variable.

Android/DEX, a dashboard, REST compatibility, arbitrary bytecode transformations
and target execution are outside the supported workflow. Archive counts do
not model JVM dependency resolution or multi-release runtime selection.
