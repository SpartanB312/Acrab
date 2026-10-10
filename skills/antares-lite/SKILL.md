---
name: antares-lite
description: Coordinate JVM JAR analysis and guarded method patching through Antares Lite MCP. Establish source identity, select bounded expert workflows and host-supported models, reconcile evidence, and verify reconstructed or patched artifacts.
---

# Antares Lite

This is the main-agent entrypoint for JVM archive analysis and schema v2
method patching. The five specialists are roles over the same MCP registry;
templates do not register tools or establish a connection. Android/DEX
analysis is unsupported. A role can be performed locally; this skill does not
authorize spawning agents.

Read [the coordinator workflow](workflows/coordinator.md) for task decisions
and [the shared contract](workflows/common.md) for identity and evidence.
Load only the specialist resources needed for the question. For model
selection, read [model routing](references/model-routing.md) and its
[machine-readable ratings](references/model-routing.json).

## Intelligence requirement

Minimum **4/5**, recommended **4/5**, escalation **5/5** for conflicting
cross-method evidence, irreversible publication or complex reconstruction.
These are reasoning requirements, not model benchmarks or confidence scores.
Use the host's supported inventory and preserve explicit user choices.

## Establish the task

1. Identify the requested result and scope: inventory, exact method evidence,
   finder candidates, reconstructed source, preview or patch application.
   Preserve an explicit backend choice and existing permission to apply.
   Analysis or preview does not authorize application.
2. Use the connected server. When opening a connection, initialize and discover
   `tools/list`; inspect the schema rather than guessing parameters.
   Send `notifications/initialized` and retain the HTTP session header.
   `health` answers status questions; `list_tools` lists 32 domain tools,
   whereas `tools/list` includes both platform endpoints as well.
3. Reuse a supplied project after `verify_archive_identity`. For a new archive, import
   and index through Sirius. Establish `projectId`, archive SHA-256 and
   a fresh identity check and `archive_summary` before project-dependent analysis. Capability-only
   questions need no project.
4. Choose a bounded next action that can resolve the question. Reuse evidence
   only for the same archive, exact scope and relevant tool/backend version.

## Route by the evidence needed

| Role | Technical template | Use when | Instructions |
| --- | --- | --- | --- |
| Sirius | `antares-lite-jvm-indexer` | Import, identify, index, locate classes or constants | [skill](specialists/jvm-indexer/SKILL.md), [workflow](workflows/jvm-indexer.md) |
| Rigel | `antares-lite-bytecode-analyst` | Inspect an exact method, CFG, branch or operand evidence | [skill](specialists/bytecode-analyst/SKILL.md), [workflow](workflows/bytecode-analyst.md) |
| Mira | `antares-lite-evidence-finder` | Rank and investigate obfuscation signals | [skill](specialists/evidence-finder/SKILL.md), [workflow](workflows/evidence-finder.md) |
| Vega | `antares-lite-decompiler` | Obtain or compare reconstructed text | [skill](specialists/decompiler/SKILL.md), [workflow](workflows/decompiler.md) |
| Altair | `antares-lite-bytecode-patcher` | Draft, review a preview or publish a patch | [skill](specialists/bytecode-patcher/SKILL.md), [workflow](workflows/bytecode-patcher.md) |

Prefer configured specialists for substantial domain work when delegation is
available and authorized. Supply the selected skill, its workflow and shared
contract as readable resources or inline text. A path is sufficient only when
the receiving agent can read it. If delegation is unavailable, perform the
same workflow directly; do not block an otherwise answerable request.
An explicit single-agent request stays local. Choose a model only after
delegation is authorized, and only from models actually supported by the host.

After Sirius establishes identity, independent Mira and Vega questions may run
in parallel. Rigel validates specific candidate or reconstruction claims.
Altair depends on source anchors and a concrete change; patches on the same
source do not compose automatically. Keep user communication, scope and final
synthesis with the coordinator.

## Tool effects and boundaries

Analysis leaves the supplied source archive unchanged. `create_project` stores
an imported copy and record. Indexing uses a server cache; decompilation uses
temporary files and a cache. Draft does not rewrite a class. Preview prepares
bytes in memory and may persist idempotency bookkeeping. `apply_method_patch`
publishes a new archive and project. Do not describe every tool as having no
filesystem effects.

Use registered tools across roles when they help resolve the task. Preferred
tools express expertise, not exclusive ownership. Never execute target code,
class initializers, bootstrap methods or native code in these static workflows.

## Synthesize the result

Carry source identity, exact descriptors, source instruction indexes, backend
metadata and coverage into the answer. Distinguish facts, heuristic candidates,
reconstruction and unknowns. Finder confidence is not a probability; a valid
preview is not proof of runtime correctness.

Record caller/field assumptions separately from conclusions. Use real operand
producer sets for branches; adjacency is not provenance. Compare rebuilt
structure with explicit naming maps. Compilation and zero finder residue are
separate checks from behavior. External fixture probes require task-specific
authorization and stay outside these static MCP workflows.

Resolve disagreement by checking the same source and descriptor first, then
bytecode at disputed indexes. Report what met the requested goal, what remains
unknown and an action that would address that gap. For patching, state the
completed stage and name the output only after application and postchecks.


