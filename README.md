# Beta Scorpii

Acrab 1.4 was formerly known as **Antares Lite**.

Acrab is a JVM and Android static-analysis and guarded JVM method-patching server for
the Model Context Protocol (MCP). It lets coding agents and human reviewers
inspect JAR archives, gather exact method evidence, triage obfuscation
patterns, reconstruct Java source, prepare controlled method patches, inspect
DEX/APK/AAB and manifests, and perform requested trusted-tool conversions.

The server is intentionally conservative. It does not execute target code, it
keeps facts, heuristic candidates, and reconstructed source separate, and it
never modifies an imported archive. Method patching is a draft, preview, and
apply workflow that publishes a new artifact only after identity and verifier
checks pass.

## Why Acrab

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
- **Android evidence:** ports Antares 1.3.5 DEX/APK/AAB and manifest analysis,
  plus optional trusted dex2jar, smali/baksmali, D8/dx and Jasmin workflows.
- **Agent-ready:** ships a main-agent skill, six specialist roles, task-focused
  workflows, role-specific intelligence requirements and model routing guidance
  for authorized delegation.
- **Traceable evidence:** binds method evidence to source hashes and records
  assumptions, analysis versions, and coverage limits.

## Requirements

- JDK 21 or newer
- A Git checkout with the bundled Gradle wrapper
- An MCP client supporting stdio or Streamable HTTP (JSON and SSE responses)

## Quick Start

Build and install the runnable distribution:

```powershell
.\gradlew.bat :acrab-app:installDist
```

```sh
./gradlew :acrab-app:installDist
```

The launcher is created at:

```text
acrab-app/build/install/acrab/bin/
```

### Connect With stdio

Configure an MCP client to launch the executable without arguments. Use the
full launcher path in the client configuration, for example:

```json
{
  "mcpServers": {
    "acrab": {
      "command": "/absolute/path/to/Acrab/acrab-app/build/install/acrab/bin/acrab"
    }
  }
}
```

On Windows, use the `acrab.bat` launcher path. The process reads one
newline-delimited JSON-RPC 2.0 object per line from stdin and writes one JSON
response per line to stdout.

### Connect With HTTP

Start the HTTP transport:

```powershell
.\acrab-app\build\install\acrab\bin\acrab.bat http
```

```sh
./acrab-app/build/install/acrab/bin/acrab http
```

Send JSON-RPC requests to:

```text
http://127.0.0.1:8080/mcp
```

Readiness is available at `GET /health`. Connect a Streamable HTTP MCP client
directly to `/mcp`; it must accept both `application/json` and
`text/event-stream`. Tool calls return request-scoped SSE responses with a
final JSON-RPC message. Discovery and tool listings return JSON.

Protocol `2026-07-28` uses `server/discover`, per-request metadata and matching
HTTP headers, without initialization or sessions. Clients using
`2025-11-25`, `2025-06-18` or `2025-03-26` initialize without a session header,
retain the server-issued `Mcp-Session-Id`, then send
`notifications/initialized` (HTTP 202, no body). Legacy sessions can be closed
with `DELETE /mcp`. GET streams are available only for legacy sessions;
streams are not resumable.

Connect your MCP client directly to this endpoint. See the
[quickstart](docs/acrab-manual/USER-QUICKSTART.md) for complete examples.

### First Analysis Steps

1. Connect the MCP client using its supported protocol, then call `tools/list`
   to inspect the schemas exposed by the connected server.
2. Use `create_project` to import a JAR, DEX/APK/AAB, multidex ZIP or manifest
   XML as Base64 bytes, preserving the original filename extension.
3. Record the returned `projectId` and SHA-256 checksum.
4. Verify identity with `verify_archive_identity`. Index JARs and use
   `archive_summary` for JVM inventory; use `android_dex_summary` for Android
   bytecode or `android_manifest_summary` for manifest questions, without a JVM index.
5. Locate a class with `list_classes` or `search_classes`, inspect its methods
   with `class_summary`, and request exact bytecode evidence with
   `method_bytecode`.

The [user quickstart](docs/acrab-manual/USER-QUICKSTART.md) contains
complete JSON-RPC examples.

## Tool Surface

Acrab registers 56 endpoints: 54 domain tools plus the `health` and
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
| Android evidence | DEX inventory, classes, strings, invocations, exact instructions, sensitive-API candidates and manifest summary/raw/findings |
| Android artifacts | Trusted conversion/assembly, ASM checks, checksum repair, class headers and `import_android_artifact` |

See the [tool reference](docs/acrab-manual/TOOL-REFERENCE.md) for
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
6. Apply the exact authorized request. Acrab writes a new artifact and
   output project; the imported archive remains unchanged.

The patch verifier performs offline structural and dataflow checks. It does
not prove runtime linking, deployment compatibility, or intended behavior.
Signed JARs and target multi-release overrides are unsupported.

For operation schemas, recovery rules, and postchecks, see the
[method patching guide](docs/acrab-manual/METHOD-PATCHING.md).

## Specialist Workflows

The bundled agent resources organize analysis into six roles. They are
client-side instructions over the same MCP registry, not separate permission
systems or server processes.

| Star | Role | Responsibility |
| --- | --- | --- |
| Sirius | JVM indexer | Project identity, import, indexing, inventory, and bounded retrieval |
| Rigel | Bytecode analyst | Exact method evidence, CFG, stack, and constant facts |
| Mira | Evidence finder | Obfuscation candidates, pagination, and coverage accounting |
| Vega | Decompiler | Reconstruction, backend comparison, and extraction caveats |
| Altair | Bytecode patcher | Drafts, previews, authorized application, and output checks |
| Canopus | Android analyst | DEX/APK/AAB and manifest evidence, authorized conversion and artifact handoffs |

Start with the [Acrab skill](skills/acrab/SKILL.md). The
[specialist workflow guide](docs/acrab-manual/SPECIALIST-WORKFLOWS.md)
explains task decomposition, evidence levels, handoffs, conflict resolution,
and completion criteria. Clients without subagent support can follow the same
workflows directly. Roles are optional responsibilities, not a pipeline that
runs every tool. Choose checks for the requested result, preserve user model
and single-agent choices, and delegate only when authorized.

[Intelligence requirements](skills/acrab/references/model-routing.md)
and their [JSON representation](skills/acrab/references/model-routing.json)
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
  `ACRAB_ALLOW_REMOTE=true`, a configured bearer token, explicit allowed
  hosts, and a TLS-protected trust boundary. Host and Origin checks also apply
  on loopback. Browser origins are restricted to same-origin or an explicit
  allowlist; forwarded headers are not trusted.
- HTTP bodies, workers, SSE streams and legacy sessions have configurable
  bounds. Cancellation and timeouts attempt to interrupt observational tools;
  only modern stream disconnects signal cancellation. Import, indexing and
  patch operations finish without interruption.
  Reconcile an uncertain write outcome before retrying.
- Preview IDs bind source identity, canonical request, engine version, and
  prepared class bytes, and can be consumed only once.
- Android conversions run only explicitly configured trusted tools. A
  successful command is not sufficient: output validation and entry coverage
  matter. Conversion creates an artifact, not an in-place rewrite or automatic
  new project. Read-only analysis does not authorize conversion or execution.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `ACRAB_HTTP_HOST` | `127.0.0.1` | HTTP bind address |
| `ACRAB_HTTP_PORT` | `8080` | HTTP port |
| `ACRAB_ALLOW_REMOTE` | `false` | Permit non-loopback binding |
| `ACRAB_MAX_HTTP_REQUEST_BYTES` | `180005548` | Maximum UTF-8 request body size, including Base64 overhead |
| `ACRAB_HTTP_AUTH_TOKEN` | unset | Require `Authorization: Bearer <token>`; mandatory for remote binding |
| `ACRAB_HTTP_ALLOWED_HOSTS` | `127.0.0.1,localhost,::1` | Accepted Host names, without ports |
| `ACRAB_HTTP_ALLOWED_ORIGINS` | empty | Additional browser origins, comma-separated |

Projects are stored under `data` relative to the server working directory.
Imported archives have a separate 128 MiB decoded-size limit. The default HTTP
body budget accommodates its Base64 encoding plus 1 MiB of envelope overhead.
See [HTTP configuration](docs/acrab-manual/USER-QUICKSTART.md#http-configuration)
for worker, stream, session and timeout settings. Choose limits for the JVM
heap and expected archive sizes.

Android conversion settings use `ACRAB_ANDROID_*`; see the
[Android guide](docs/acrab-manual/ANDROID.md#configuration). No converter is
required for static DEX or manifest analysis, and no converter executable is
bundled. Derived artifacts can enter a new project through the same MCP connection.

## Scope

Supported:

- JVM JAR bytecode analysis
- Static method, control-flow, and stack evidence
- Bounded numeric constant analysis
- Obfuscation candidate triage
- CFR and Vineflower reconstruction
- Guarded schema v2 method patching
- stdio and Streamable HTTP MCP transports with request-scoped SSE
- Static DEX/APK/AAB/multidex and XML/AXML/protobuf manifest inspection
- Optional trusted Android conversion/assembly and derived-project handoffs

Not supported:

- Android target execution, APK signing or deployment
- Target-code execution or dynamic tracing
- Arbitrary bytecode rewriting
- Dashboards and REST compatibility APIs
- Deprecated `2024-11-05` HTTP+SSE transport and SSE event replay
- Native database dependencies
- Runtime multi-release class selection or dependency resolution

## Documentation

- [Manual overview](docs/acrab-manual/README.md)
- [User quickstart](docs/acrab-manual/USER-QUICKSTART.md)
- [Tool reference](docs/acrab-manual/TOOL-REFERENCE.md)
- [Specialist workflows](docs/acrab-manual/SPECIALIST-WORKFLOWS.md)
- [Method patching](docs/acrab-manual/METHOD-PATCHING.md)
- [Static analysis capabilities](docs/acrab-manual/ANALYSIS-AND-VERIFICATION.md)
- [Android analysis and conversion](docs/acrab-manual/ANDROID.md)
- [Agent skill entrypoint](skills/acrab/SKILL.md)

## Project Layout

```text
acrab-core        Registry, configuration, and runtime context
acrab-analysis    JVM/Android evidence, converters, finders, decompilation, patching
acrab-mcp         stdio and HTTP JSON-RPC transports
acrab-app         Tool registration, launcher, and distribution assembly
skills/acrab      Coordinating skill, specialist skills, and workflows
docs/acrab-manual  User, tool, workflow, and patching documentation
```

## Development

Run the complete build and test suite:

```powershell
.\gradlew.bat build
```

Create installable and distributable artifacts:

```powershell
.\gradlew.bat :acrab-app:installDist :acrab-app:distZip :acrab-app:distTar
```

Distribution archives are written to
`acrab-app/build/distributions/`. The current release version is
**1.4**. ZIP/TAR packages include the manual, skills, model routing references,
agent templates, generated catalog and license.

## License

Project code is released under the [MIT License](LICENSE). Vendored AAPT2
schemas and their generated code retain the
[AOSP Apache-2.0 license](licenses/AOSP-Apache-2.0.txt) and
[notice](licenses/AOSP-NOTICE.txt).
