# Specialist Workflows

The five specialists organize evidence responsibilities over shared Acrab
MCP tools. Agent templates do not enforce a tool allowlist, install themselves
or start a server. The coordinator retains user communication, authorization,
scope and final synthesis.

## Resources and configuration

The root skill is the main-agent entrypoint. It handles scope, source identity
and final synthesis without a separate coordinator configuration. Status and
tool discovery can call `health` or `tools/list` directly. Domain requests load
only the relevant role and shared rules. Single-agent requests perform those
workflows locally without a formal handoff or spawning specialists.

Keep the [skill tree](../../skills/acrab/SKILL.md) together. Each role
loads its skill, dedicated workflow and
[shared contract](../../skills/acrab/workflows/common.md).

| Role | Agent ID | Skill | Workflow |
| --- | --- | --- | --- |
| Sirius | `acrab-jvm-indexer` | [Indexing](../../skills/acrab/specialists/jvm-indexer/SKILL.md) | [Identify and locate](../../skills/acrab/workflows/jvm-indexer.md) |
| Rigel | `acrab-bytecode-analyst` | [Bytecode](../../skills/acrab/specialists/bytecode-analyst/SKILL.md) | [Check a method claim](../../skills/acrab/workflows/bytecode-analyst.md) |
| Mira | `acrab-evidence-finder` | [Finders](../../skills/acrab/specialists/evidence-finder/SKILL.md) | [Triage and collect](../../skills/acrab/workflows/evidence-finder.md) |
| Vega | `acrab-decompiler` | [Reconstruction](../../skills/acrab/specialists/decompiler/SKILL.md) | [Reconstruct and qualify](../../skills/acrab/workflows/decompiler.md) |
| Altair | `acrab-bytecode-patcher` | [Patching](../../skills/acrab/specialists/bytecode-patcher/SKILL.md) | [Draft, review, publish](../../skills/acrab/workflows/bytecode-patcher.md) |

The `agents/acrab-*.toml` files are host-specific agent configuration
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

The main agent selects a model using the role's reasoning requirements and the
actual assignment. Ratings are task demands on a 1-5 scale, not measured model
scores. Retrieval, analysis and mutation can require different capabilities.

| Role | Minimum | Recommended | Escalated |
| --- | --- | --- | --- |
| Coordinator | 4 | 4 | 5 |
| Sirius | 2 | 2 | 3 |
| Mira | 3 | 3 | 4 |
| Rigel | 4 | 4 | 5 |
| Vega | 3 | 4 | 5 |
| Altair | 4 | 4 | 5 |

For authorized delegation that needs model selection, use
[routing guidance](../../skills/acrab/references/model-routing.md) and
the host's supported inventory and capability assessments.
[JSON requirements](../../skills/acrab/references/model-routing.json)
support programmatic consumers. Preserve user model and effort choices; local
or inherited-model work needs no additional lookup. Increased reasoning
complexity can trigger escalation, but missing evidence remains a limitation.
Ratings never authorize delegation or expand the task.

## Task decomposition

Start with the user's question, not a checklist that runs every tool. The
examples below are choices, not required stages. Caller/field investigation,
string recovery, backend comparison and structural comparison apply only when
the requested result needs them. Source retrieval and bounded triage can finish
without these extra checks. Preserve user model and effort choices; delegation
uses the host's supported settings and requires authorization.

| Request | First useful work | Dependent check | Completion evidence |
| --- | --- | --- | --- |
| New JAR and class lookup | Sirius imports/indexes and queries | Domain expert only if needed | Verified identity, summary and bounded targets |
| Explain a method/branch | Rigel resolves descriptor and inspects bytecode | CFG or adjacent claim check | Indexed observations and explicit unknowns |
| Trace caller or field context | Rigel queries the relevant references or callsite | Inspect sites needed for the requested scope; preserve assumptions | Source sites, contextual values and explicit limits |
| Find suspicious transforms | Mira chooses categories and coverage | Rigel checks selected findings | Scan/payload/review counts and candidate provenance |
| Reconstruct source | Vega selects backend and exact scope | Rigel for disputed semantics | Source, backend/version, extraction/failure caveats |
| Compare supplied archives | Sirius verifies both identities; Vega/Rigel compare structure | Explicit naming maps and bounded differences | Structural comparison results and limitations |
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

## Judge completion

Complete means the requested deliverable and its checks passed. Partial means
requested coverage or a required check remains missing. Blocked means no useful
authorized step can proceed. A bounded explanation may be complete while
runtime behavior remains explicitly unknown; an exhaustive audit cannot be
completed by silently changing its scope to a shortlist.

For final patch reporting, distinguish prepared preview, published output and
postchecked output. State `asm-static` verification separately from intended
behavior. None of these workflows executes the target archive.
