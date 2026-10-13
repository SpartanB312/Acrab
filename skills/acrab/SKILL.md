---
name: acrab
description: Analyze JVM JARs or Android DEX/APK/AAB and manifests through Acrab MCP, prepare guarded JVM method patches, and perform requested trusted-tool conversions. Coordinate specialists when the task needs them.
---

# Acrab

This is the main-agent entrypoint for JVM and Android analysis, requested
conversions and schema v2 JVM method patching. The six specialists are roles
over the same MCP registry; templates do not register tools or establish a
connection. A role can be performed locally; this skill does not
authorize spawning agents.

For status or tool discovery, call `health` or `tools/list` directly; no project
or specialist workflow is needed. For domain work, read
[the shared contract](workflows/common.md) and only the relevant specialist
resources below. Roles organize expertise, not a mandatory pipeline. Choose
tools and checks for the requested result; a lookup or reconstruction does not
imply an archive audit or patch. Local work uses the current model.

Read [model routing](references/model-routing.md) when selecting a model for
authorized delegation. [JSON requirements](references/model-routing.json) are
for programmatic consumers, not a second required read.

## Intelligence requirement

For substantial coordination: minimum **4/5**, recommended **4/5**, escalation
**5/5** for complex cross-role reasoning or conflicting evidence. These are
task requirements, not measured model scores. Preserve user model and effort
choices; a stronger model does not supply missing evidence.

## Establish the task

1. Identify the requested result and scope: inventory, exact method evidence,
   finder candidates, reconstructed source, manifest evidence, conversion,
   preview or patch application.
   Preserve an explicit backend choice and existing permission to apply.
   Analysis or preview does not authorize application.
2. Use the connected server and discover `tools/list`; inspect the schema
   rather than guessing parameters. Let the client manage transport negotiation:
   `2026-07-28` uses per-request metadata, while supported legacy clients
   initialize, send `notifications/initialized` and retain the HTTP session ID.
   HTTP tool responses use SSE; use an MCP client supporting JSON and SSE.
   `health` answers status questions; `list_tools` lists 54 domain tools,
   whereas `tools/list` includes both platform endpoints as well.
3. Reuse a supplied project after `verify_archive_identity`. For a new archive, import
   using `create_project`. Index JARs using Sirius; route Android inputs to
   Canopus without JVM indexing. Establish `projectId`, archive SHA-256
   and a fresh identity check. Use `archive_summary` for JVM inventory or
   `android_dex_summary` for Android bytecode inventory;
   reuse an existing summary rather than repeating it for every role.
   Capability-only questions need no project.
4. Choose a bounded next action that can resolve the question. Reuse evidence
   only for the same archive, exact scope and relevant tool/backend version.

## Route by the evidence needed

| Role | Technical template | Use when | Instructions |
| --- | --- | --- | --- |
| Sirius | `acrab-jvm-indexer` | Import, identify, index, locate classes or constants | [skill](specialists/jvm-indexer/SKILL.md), [workflow](workflows/jvm-indexer.md) |
| Rigel | `acrab-bytecode-analyst` | Inspect an exact method, CFG, branch or operand evidence | [skill](specialists/bytecode-analyst/SKILL.md), [workflow](workflows/bytecode-analyst.md) |
| Mira | `acrab-evidence-finder` | Rank and investigate obfuscation signals | [skill](specialists/evidence-finder/SKILL.md), [workflow](workflows/evidence-finder.md) |
| Vega | `acrab-decompiler` | Obtain or compare reconstructed text | [skill](specialists/decompiler/SKILL.md), [workflow](workflows/decompiler.md) |
| Altair | `acrab-bytecode-patcher` | Draft, review a preview or publish a patch | [skill](specialists/bytecode-patcher/SKILL.md), [workflow](workflows/bytecode-patcher.md) |
| Canopus | `acrab-android` | Inspect DEX/APK/AAB/manifests or create an authorized converted artifact | [skill](specialists/android/SKILL.md), [workflow](workflows/android.md) |

Perform a role locally or delegate when authorized and useful. Supply the
selected skill, its workflow and shared
contract as readable resources or inline text. A path is sufficient only when
the receiving agent can read it. If delegation is unavailable, perform the
same workflow directly; do not block an otherwise answerable request.
Preserve user choices for agents, models and reasoning effort. An explicit
single-agent request stays local. Use only host-supported settings when a
delegated task needs model selection.

After identity is established, independent authorized work may run in parallel.
Cross-check a candidate or reconstruction only when the requested conclusion
depends on it. Altair needs source anchors and a concrete change; patches on
the same source do not compose automatically. Keep user communication, scope
and final synthesis with the main agent.

## Tool effects and boundaries

Analysis leaves the supplied source archive unchanged. `create_project` stores
an imported copy and record. Indexing uses a server cache; decompilation uses
temporary files and a cache. Draft does not rewrite a class. Preview prepares
bytes in memory and may persist idempotency bookkeeping. `apply_method_patch`
publishes a new archive and project. Do not describe every tool as having no
filesystem effects.

Android conversions, assembly, checksum repair and class-version changes create
separate artifacts and run bundled verified Java converters or explicit trusted
external overrides. Installed distributions need no converter setup. They need
authorization for the requested output, not JVM patch preview tokens.
`import_android_artifact` creates a new project from a project-owned artifact.
Read-only inspection does not authorize conversion or execution.

Use registered tools across roles when they help resolve the task. Preferred
tools express expertise, not exclusive ownership. Never execute target code,
class initializers, bootstrap methods or native code in these static workflows.

## Synthesize the result

Carry source identity, exact descriptors, source instruction indexes, backend
metadata and coverage into the answer. Distinguish facts, heuristic candidates,
reconstruction and unknowns. Finder confidence is not a probability; a valid
preview is not proof of runtime correctness.

Record caller/field assumptions separately from conclusions. Use real operand
producer sets for branches; adjacency is not provenance.

Resolve disagreement by checking the same source and descriptor first, then
bytecode at disputed indexes. Report what met the requested goal, what remains
unknown and an action that would address that gap. For patching, state the
completed stage and name the output only after application and postchecks.


