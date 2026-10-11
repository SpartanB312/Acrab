# Antares Lite

Antares Lite is a JVM bytecode analysis and guarded method-patching server for
the Model Context Protocol (MCP). It lets coding agents and human reviewers
inspect JAR archives, gather exact method evidence, triage obfuscation
patterns, reconstruct Java source, and prepare controlled method patches.

The server is intentionally conservative. It does not execute target code, it
keeps facts, heuristic candidates, and reconstructed source separate, and it
never modifies an imported archive. Method patching is a draft, preview, and
apply workflow that publishes a new artifact only after identity and verifier
checks pass.

## Why Antares Lite

- **MCP-native:** exposes stdio and loopback-first HTTP JSON-RPC transports
  with complete tool schemas.
- **Evidence-oriented:** verifies current archive bytes and provides exact method
  addressing, CFG, actual operand producers, direct references and conditional
  numeric analysis with recorded assumptions.
- **Obfuscation triage:** detects numeric decoding, string transforms, opaque
  guards, control-flow flattening, and invokedynamic dispatch patterns.
- **Reconstruction:** bundles CFR and Vineflower, declaration-aware method
  extraction, bounded static string recovery and mapped structural comparison.
- **Guarded patching:** binds every operation to source instruction indexes,
  archive checksums, method fingerprints, debug metadata, and a single-use
  preview.
- **Agent-ready:** ships a main-agent skill, five specialist roles, task-focused
  workflows, role-specific intelligence requirements and model routing guidance
  for authorized delegation.
- **Traceable evidence:** binds method evidence to source hashes and records
  assumptions, analysis versions, and coverage limits.

## Requirements

- JDK 21 or newer
- A Git checkout with the bundled Gradle wrapper
- An MCP client for stdio, or any JSON-capable HTTP client for HTTP transport

## Quick Start

Build and install the runnable distribution:

```powershell
.\gradlew.bat :antares-lite-app:installDist
```

```sh
./gradlew :antares-lite-app:installDist
```

The launcher is created at:

```text
antares-lite-app/build/install/antares-lite/bin/
```

### Connect With stdio

Configure an MCP client to launch the executable without arguments. Use the
full launcher path in the client configuration, for example:

```json
{
  "mcpServers": {
    "antares-lite": {
      "command": "/absolute/path/to/Antares-Lite/antares-lite-app/build/install/antares-lite/bin/antares-lite"
    }
  }
}
```

On Windows, use the `antares-lite.bat` launcher path. The process reads one
newline-delimited JSON-RPC 2.0 object per line from stdin and writes one JSON
response per line to stdout.

### Connect With HTTP

Start the HTTP transport:

```powershell
.\antares-lite-app\build\install\antares-lite\bin\antares-lite.bat http
```

```sh
./antares-lite-app/build/install/antares-lite/bin/antares-lite http
```

Send JSON-RPC requests to:

```text
http://127.0.0.1:8080/mcp
```

Readiness is available at `GET /health`. Responses are JSON documents; the
server does not use SSE. Initialization returns a unique `Mcp-Session-Id`;
retain it on every later request. Send `notifications/initialized` after
initialization (HTTP 202, no body). Close sessions with `DELETE /mcp`.

Connect your MCP client directly to this endpoint. See the
[quickstart](docs/antares-lite-manual/USER-QUICKSTART.md) for complete examples.

### First Analysis Steps

1. Call MCP `initialize`.
2. Send `notifications/initialized`, then call MCP `tools/list` to inspect the schemas exposed by the connected
   server.
3. Use `create_project` to import a JAR as Base64 bytes.
4. Record the returned `projectId` and SHA-256 checksum.
5. Verify identity with `verify_archive_identity` and index the archive. Use
   `archive_summary` when inventory is needed.
6. Locate a class with `list_classes` or `search_classes`, inspect its methods
   with `class_summary`, and request exact bytecode evidence with
   `method_bytecode`.

The [user quickstart](docs/antares-lite-manual/USER-QUICKSTART.md) contains
complete JSON-RPC examples.

## Tool Surface

Antares Lite registers 34 endpoints: 32 domain tools plus the `health` and
`list_tools` platform endpoints. Runtime `tools/list` is always authoritative.

| Area | Tools |
| --- | --- |
| Project and indexing | `create_project`, `list_projects`, `verify_archive_identity`, `index_archive`, `archive_summary` |
| Reconnaissance | `list_classes`, `search_classes`, `search_strings`, `class_summary` |
| Method evidence | `method_bytecode`, `method_cfg`, `method_stack_slice`, `cfg_constant_propagation` |
| References and context | `method_references`, `field_references`, `method_call_context` |
| Reconstruction | `list_decompiler_backends`, `decompile_class`, `decompile_method` |
| Numeric and string evidence | `find_number_secrets`, `find_string_transforms` |
| Static recovery and comparison | `recover_strings`, `compare_structure` |
| Control-flow evidence | `find_opaque_predicates`, `find_control_flow_flattening`, `find_invokedynamic_dispatch` |
| Aggregation | `evidence_summary`, `finder_catalog` |
| Guarded patching | `patch_catalog`, `method_patch_draft`, `method_patch_preview`, `apply_method_patch` |
| Platform status | `health`, `list_tools` |

See the [tool reference](docs/antares-lite-manual/TOOL-REFERENCE.md) for
arguments, result fields, bounds, state effects, and interpretation limits.

## Guarded Method Patching

Patching supports small, evidence-backed method edits, not arbitrary bytecode
rewrites.

1. Import and index a JAR.
2. Inspect the target method and copy its source instruction indexes.
3. Draft an operation using an opcode supported by `patch_catalog`.
4. Include the archive, method, instruction, and debug-metadata fingerprints
   returned by the server.
5. Preview the prepared class, review its operation diff and verifier
   diagnostics, and receive a single-use preview ID.
6. Apply the exact authorized request. Antares Lite writes a new artifact and
   output project; the imported archive remains unchanged.

The patch verifier performs offline structural and dataflow checks. It does
not prove runtime linking, deployment compatibility, or intended behavior.
Signed JARs and target multi-release overrides are unsupported.

For operation schemas, recovery rules, and postchecks, see the
[method patching guide](docs/antares-lite-manual/METHOD-PATCHING.md).

## Specialist Workflows

The bundled agent resources organize analysis into five roles. They are
client-side instructions over the same MCP registry, not separate permission
systems or server processes.

| Star | Role | Responsibility |
| --- | --- | --- |
| Sirius | JVM indexer | Project identity, import, indexing, inventory, and bounded retrieval |
| Rigel | Bytecode analyst | Exact method evidence, CFG, stack, and constant facts |
| Mira | Evidence finder | Obfuscation candidates, pagination, and coverage accounting |
| Vega | Decompiler | Reconstruction, backend comparison, and extraction caveats |
| Altair | Bytecode patcher | Drafts, previews, authorized application, and output checks |

Start with the [Antares Lite skill](skills/antares-lite/SKILL.md). The
[specialist workflow guide](docs/antares-lite-manual/SPECIALIST-WORKFLOWS.md)
explains task decomposition, evidence levels, handoffs, conflict resolution,
and completion criteria. Clients without subagent support can follow the same
workflows directly. Roles are optional responsibilities, not a pipeline that
runs every tool. Choose checks for the requested result, preserve user model
and single-agent choices, and delegate only when authorized.

[Intelligence requirements](skills/antares-lite/references/model-routing.md)
and their [JSON representation](skills/antares-lite/references/model-routing.json)
help the main agent select an appropriately capable supported model for each
role. The requirements describe task demands, not measured model scores.

## Security Model

- Imported JARs are copied into a server workspace. Analysis and patching never
  rewrite the imported source archive.
- Target classes, class initializers, native code, decoders, and bootstrap
  methods are never executed.
- Output limits and truncation flags are reported instead of being treated as
  exhaustive coverage.
- Finder confidence is an advisory signal, not a probability or a
  maliciousness verdict.
- Decompiled source is reconstruction. Bytecode evidence remains authoritative
  when the two disagree.
- Argument/field bindings are explicit assumptions about a selected context.
  Direct references do not resolve reflection, virtual dispatch or unseen callers.
- A structural match, zero detector findings or successful static verification
  does not establish behavioral equivalence. Evidence checksums detect
  alteration, not author authenticity or semantic correctness.
- HTTP binds to loopback by default. Non-loopback binding requires
  `ANTARES_LITE_ALLOW_REMOTE=true` and must be placed behind an authenticated
  trust boundary.
- Preview IDs bind source identity, canonical request, engine version, and
  prepared class bytes, and can be consumed only once.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `ANTARES_LITE_HTTP_HOST` | `127.0.0.1` | HTTP bind address |
| `ANTARES_LITE_HTTP_PORT` | `8080` | HTTP port |
| `ANTARES_LITE_ALLOW_REMOTE` | `false` | Permit non-loopback binding |
| `ANTARES_LITE_MAX_HTTP_REQUEST_BYTES` | `134217728` | Maximum HTTP request body size |

Projects are stored under `data` relative to the server working directory.
Imported archives have a separate 128 MiB decoded-size limit.

## Scope

Supported:

- JVM JAR bytecode analysis
- Static method, control-flow, and stack evidence
- Bounded numeric constant analysis
- Obfuscation candidate triage
- CFR and Vineflower reconstruction
- Guarded schema v2 method patching
- stdio and JSON-over-HTTP MCP transports

Not supported:

- Android, DEX, or APK-specific analysis
- Target-code execution or dynamic tracing
- Arbitrary bytecode rewriting
- Dashboards, REST compatibility APIs, and SSE
- Native database dependencies
- Runtime multi-release class selection or dependency resolution

## Documentation

- [Manual overview](docs/antares-lite-manual/README.md)
- [User quickstart](docs/antares-lite-manual/USER-QUICKSTART.md)
- [Tool reference](docs/antares-lite-manual/TOOL-REFERENCE.md)
- [Specialist workflows](docs/antares-lite-manual/SPECIALIST-WORKFLOWS.md)
- [Method patching](docs/antares-lite-manual/METHOD-PATCHING.md)
- [Static analysis capabilities](docs/antares-lite-manual/ANALYSIS-AND-VERIFICATION.md)
- [Agent skill entrypoint](skills/antares-lite/SKILL.md)

## Project Layout

```text
antares-lite-core       Registry, configuration, and runtime context
antares-lite-analysis   Indexing, evidence, finders, decompilation, patching
antares-lite-mcp        stdio and HTTP JSON-RPC transports
antares-lite-app        Tool registration, launcher, and distribution assembly
skills/antares-lite     Coordinating skill, specialist skills, and workflows
docs/antares-lite-manual  User, tool, workflow, and patching documentation
```

## Development

Run the complete build and test suite:

```powershell
.\gradlew.bat build
```

Create installable and distributable artifacts:

```powershell
.\gradlew.bat :antares-lite-app:installDist :antares-lite-app:distZip :antares-lite-app:distTar
```

Distribution archives are written to
`antares-lite-app/build/distributions/`. The current release version is
**1.3.1**. ZIP/TAR packages include the manual, skills, model routing references,
agent templates, generated catalog and license.

## License

Released under the [MIT License](LICENSE).
