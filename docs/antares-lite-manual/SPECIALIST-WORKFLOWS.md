# Specialist Workflows

The five specialists organize evidence responsibilities over shared Antares Lite
MCP tools. Agent templates do not enforce a tool allowlist, install themselves
or start a server. The coordinator retains user communication, authorization,
scope and final synthesis.

## Resources and configuration

The root skill is the main-agent entrypoint, with a dedicated
[coordinator workflow](../../skills/antares-lite/workflows/coordinator.md).
It owns source verification, evidence reconciliation and independent artifact
checks. Simple requests load only the relevant role; single-agent requests
perform those workflows locally without spawning specialists.

Keep the [skill tree](../../skills/antares-lite/SKILL.md) together. Each role
loads its skill, dedicated workflow and
[shared contract](../../skills/antares-lite/workflows/common.md).

| Role | Agent ID | Skill | Workflow |
| --- | --- | --- | --- |
| Sirius | `antares-lite-jvm-indexer` | [Indexing](../../skills/antares-lite/specialists/jvm-indexer/SKILL.md) | [Identify and locate](../../skills/antares-lite/workflows/jvm-indexer.md) |
| Rigel | `antares-lite-bytecode-analyst` | [Bytecode](../../skills/antares-lite/specialists/bytecode-analyst/SKILL.md) | [Check a method claim](../../skills/antares-lite/workflows/bytecode-analyst.md) |
| Mira | `antares-lite-evidence-finder` | [Finders](../../skills/antares-lite/specialists/evidence-finder/SKILL.md) | [Triage and collect](../../skills/antares-lite/workflows/evidence-finder.md) |
| Vega | `antares-lite-decompiler` | [Reconstruction](../../skills/antares-lite/specialists/decompiler/SKILL.md) | [Reconstruct and qualify](../../skills/antares-lite/workflows/decompiler.md) |
| Altair | `antares-lite-bytecode-patcher` | [Patching](../../skills/antares-lite/specialists/bytecode-patcher/SKILL.md) | [Draft, review, publish](../../skills/antares-lite/workflows/bytecode-patcher.md) |

The `agents/antares-lite-*.toml` files are host-specific agent configuration
templates. Register them through the host's supported agent configuration and
make the same MCP server available to each role; they are not MCP wire messages.
Nested specialist skills are role resources in this bundle, not an assumption
that a host discovers nested skills automatically.

When the receiver can read these files, supply their paths. For a remote or
isolated receiver, inject the full selected skill/workflow/shared contract.
Do not impose an inline-text gate on a receiver with working file access.
When delegation is unavailable or unauthorized, the coordinator follows the
same workflow directly.

## Intelligence and model selection

Every role has minimum, recommended and escalation intelligence requirements
on a 1-5 task scale. These are reasoning demands, not measured model scores.

| Role | Minimum | Recommended | Escalated |
| --- | --- | --- | --- |
| Coordinator | 4 | 4 | 5 |
| Sirius | 2 | 2 | 3 |
| Mira | 3 | 3 | 4 |
| Rigel | 4 | 4 | 5 |
| Vega | 3 | 4 | 5 |
| Altair | 4 | 4 | 5 |

Use [routing guidance](../../skills/antares-lite/references/model-routing.md)
and [portable JSON ratings](../../skills/antares-lite/references/model-routing.json)
against the host's actual supported model inventory. Preserve user model and
effort choices. Require a trusted capability assessment or record inherited
unassessed fit; do not infer capability from a model name. Difficult loops,
cross-call assumptions, backend contradictions and control-flow patches trigger
escalation. Ratings never authorize delegation or supply missing evidence.

## Task decomposition

Start with the user's question, not a checklist that runs every tool.

| Request | First useful work | Dependent check | Completion evidence |
| --- | --- | --- | --- |
| New JAR and class lookup | Sirius imports/indexes and queries | Domain expert only if needed | Verified identity, summary and bounded targets |
| Explain a method/branch | Rigel resolves descriptor and inspects bytecode | CFG or adjacent claim check | Indexed observations and explicit unknowns |
| Trace argument/guard context | Rigel queries exact references and one callsite | Inspect all indexed writers, preserve assumptions | Actual producers, argument maps and conditional scope |
| Find suspicious transforms | Mira chooses categories and coverage | Rigel checks selected findings | Scan/payload/review counts and candidate provenance |
| Reconstruct source | Vega selects backend and exact scope | Rigel for disputed semantics | Source, backend/version, extraction/failure caveats |
| Validate rebuilt artifacts | Vega/Rigel compare explicit naming maps | Clean rebuild and separately authorized bounded probe | Distinct compilation, structure, finder and behavioral results |
| Preview a concrete change | Altair drafts and reviews preview | Rigel when source effect needs clarification | Bound request, hashes, diff and static checks |
| Apply that change | Altair uses existing authorization and preview | Index/check the output project and requested change | Artifact hash, verified output, successful indexing and observed change |
| Archive audit | Sirius establishes identity, then bounded independent lanes | Cross-check only material claims | Declared category/class/method coverage and remaining gaps |

Independent finder and reconstruction questions can run in parallel after
identity is established. A finder-to-bytecode check is dependent work; pass
the actual finding and exact method. Apply is serialized, and a second patch
must be prepared against the first patch's output if composition is intended.

## Dispatch and evidence

A useful task includes goal, deliverable, project/checksum, exact scope,
existing evidence, authorized stage and a stopping condition. Capability-only
work needs no source. Missing identity triggers reconnaissance; an ambiguous
descriptor triggers `class_summary`, not random method calls.

A handoff records status, completed stage, source identity, scope, tools,
evidence records, claims with evidence IDs, coverage, artifacts, limitations
and next action. Use the shared contract's structured form for delegation;
prose can carry the same facts for a direct user answer.

Maintain three distinct coverage statements: what the server scanned, what it
returned and what the specialist reviewed. Example: a finder scanned the indexed
project, returned 20 of 80 candidates, and Rigel inspected two exact methods.
That supports a two-method investigation, not an exhaustive archive verdict.

Observed instructions are facts about that method. Detector output is a
candidate. Decompiled source is reconstruction. Missing data is unknown.
A candidate can be confirmed only for the specific property established by
additional evidence; confirming its indexes is not confirming its runtime effect.

## Conflict and failure examples

- **Same name, different overload:** retrieve descriptors, repeat with the
  selected descriptor, and compare only matching source/method results.
- **Reported checksum conflict:** compare actual response fields and freshly
  recomputed stored bytes before reporting divergence; filenames and memory
  are insufficient.
- **Nearby guard mistaken for counter input:** inspect each branch's actual
  consumed producer set and destinations; preserve merged alternatives.
- **One observed caller sets a false guard:** record its exact argument/write
  context; do not conclude the field is never assigned or globally constant.
- **Two decompilers disagree:** preserve both excerpts and check source
  instructions. Matching parameter counts do not prove overload identity.
- **Finder page is truncated:** collect category pages using an exclusive cursor,
  or finish the requested top-N task with its declared gap. Summary has no cursor.
- **Finder IDs collide between overloads:** keep descriptors/indexes as part of
  evidence identity. If the cursor stalls, restart with a larger first page;
  if the 1,000-result cap still prevents collection, report that coverage gap.
- **CFG says a block is unreachable while truncated:** omitted blocks also enter
  that field in this version. Do not justify deletion from it.
- **Preview succeeds but user requested no apply:** finish with preview evidence.
  Do not publish an artifact as an incidental final step.
- **Apply times out:** reconcile visible output before further mutation. Absence
  of a response is not evidence of failure.
- **Reconstruction compiles and finders are empty:** verify disputed loop updates
  independently. These checks cannot prove behavioral fidelity.

## Judge completion

Complete means the requested deliverable and its checks passed. Partial means
requested coverage or a required check remains missing. Blocked means no useful
authorized step can proceed. A bounded explanation may be complete while
runtime behavior remains explicitly unknown; an exhaustive audit cannot be
completed by silently changing its scope to a shortlist.

For final patch reporting, distinguish prepared preview, published output and
postchecked output. State `asm-static` verification separately from intended
behavior. None of these workflows executes the target archive.
