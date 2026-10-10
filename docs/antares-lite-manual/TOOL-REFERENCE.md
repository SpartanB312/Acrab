# Tool Reference

This reference describes the bundled 1.3.0 server. Use runtime `tools/list` or
generated `catalog/tools-catalog.json` for full schemas. There are 32 domain
tools plus `health` and `list_tools`; registry "analysis tools" include import
and patching.

## Call and decode

Use MCP `tools/call` with `params.name` and `params.arguments`. Most tools
use flat arguments; method patch tools use a nested `request`. Unknown keys
and wrong primitive types are rejected.

Results contain `result.content[].text` and `result.isError`. Most text
bodies contain JSON; `health` returns plain text. Check JSON-RPC `error` and
tool `isError`, then domain status/identity/coverage. HTTP success does not
establish tool or patch success.

## Project and retrieval

| Tool | Required arguments | Optional arguments | Result and bounds |
| --- | --- | --- | --- |
| `create_project` | `fileName`, `archiveBase64` | None | ID, filename, path, `sha256`, creation time; decoded input up to 128 MiB |
| `list_projects` | None | None | Stored project records, newest first; no query/cursor |
| `verify_archive_identity` | `projectId` | `expectedSha256` | Recomputed `actualSha256`, `recordedSha256`, `matches` and optional `expectedMatches` |
| `index_archive` | `projectId` | None | ID, `archiveSha256`, class/method/field/string/invocation counts |
| `archive_summary` | `projectId` | None | Archive hash, structural counts, manifest main class, top packages |
| `list_classes` | `projectId` | `query`, `limit` | Class array; default 100, cap 1,000 |
| `search_classes` | `projectId`, `query` | `limit` | Class substring lookup; default 100, cap 1,000 |
| `search_strings` | `projectId` | `query`, `limit` | Constant references; default 100, cap 1,000 |
| `class_summary` | `projectId`, `className` | None | Fields/methods/descriptors, inheritance, flags, annotations |

Import stores a copy; it does not validate every class. Index/summary/queries
build or reuse an in-memory index. Index reuse recomputes stored archive identity
and rejects changed bytes rather than trusting a cached hash. Records persist;
import changed bytes as a new project. An identity tool response can succeed
while `matches` is false; inspect its fields before continuing.

Class/string queries use trimmed, case-insensitive substring matching.
Limits clamp to 1-1,000; supply a positive limit. Results have no cursor, total
or truncation flag. At the cap, report potentially omitted matches.
Indexed LDC/field strings expose entropy, printable ratio and classification (`plain`,
`suspicious`, `likely-encrypted`), all heuristic. Field constants may lack
a method/index. There is no resource-content search tool.

Index counts describe class entries, including versioned entries; they do not
resolve runtime multi-release selection, dependencies or class loading.

## Method evidence

All four tools require `projectId`, `className` and `methodName`, and accept
optional `methodDescriptor`. Use an exact descriptor for claims/anchors.
An overloaded name-only call fails with available descriptors.

| Tool | Additional required | Additional optional | Result and limits |
| --- | --- | --- | --- |
| `method_bytecode` | None | None | Instructions, label indexes, count, access/synthetic flags; at most 500 executable instructions |
| `method_cfg` | None | None | Entry, blocks, edges, unreachable blocks; at most 100 blocks |
| `method_stack_slice` | `instructionIndex` | `argumentSlots` | Actual consumed operands; inferred count by default, optional 0-256 JVM stack slots |
| `cfg_constant_propagation` | None | `argumentValues`, `fieldValues` | Conditional block/branch facts; at most 100 blocks |

Responses use `descriptor` for the resolved JVM descriptor, plus
`requestedDescriptor`, `methodMatchKind` (`DESCRIPTOR` or `NAME_ONLY`)
and `exactMethodMatch`. For these tools the flag means descriptor-selected.
They include `inputSha256` and `analysisVersion` for evidence reuse.

Instruction `index`, `jumpTargetIndex`, switch/label targets are raw ASM
positions. The listing omits metadata nodes without renumbering; counts include
those nodes. There is no method paging. Honor `truncated`.

Current interpretation limits:

- Truncated CFG `unreachableBlocks` includes omitted blocks as well as
  modeled unreachable blocks; edges may reference unreturned blocks.
- Stack slices trace consumed values bottom-to-top using ASM dataflow. They
  expose `slotWidth`, immediate `producerInstructionIndexes`, transitive
  `originInstructionIndexes`, parameter-local indexes and numeric values when
  known. `producerInstructionIndex` is populated only when unique; merged
  alternatives stay sets. Explicit `argumentSlots` selects the top stack slots
  and rejects requests splitting a wide operand. Check `analysisStatus`,
  warnings, `truncated` and per-slice `provenanceTruncated`. Origins/producers
  cap at 128 per value; analysis has instruction/frame budgets.
- Numeric constant propagation reports normal-flow stack/local values at block
  exits, up to 500 `instructionFacts`, and branch outcomes `taken`, `not-taken`
  or `unknown`. Unconditional `GOTO` is `taken`. Numeric arithmetic, conversions,
  JVM overflow and shifts are modeled. Fields remain unknown unless explicitly
  bound; call results and conflicting merges remain unknown. Branch facts include
  operands, condition expressions, taken/fallthrough destinations and an unknown
  reason. It does not prove that potentially throwing operations complete.
  Analysis is bounded to 10,000 instructions and two million frame cells;
  skipped/failed analysis is warned. Block stack/local payloads cap at 500 each.

These are bounded static observations, not full dataflow/runtime proofs.

`argumentValues` and `fieldValues` are objects with **string values**. Argument
keys are zero-based formal argument ordinals excluding `this`, not local slots.
Field keys are internal `owner/name.field:descriptor` identifiers for primitive
static fields. Boolean values are `"0"` or `"1"`; floating assumptions must be
finite. Supplied maps are echoed with scope `conditional-on-supplied-assumptions`.
Without bindings, scope is `intraprocedural-normal-flow`. Field bindings are
fixed read assumptions, not automatic write/caller analysis or a global proof.

## References, context and recovery

| Tool | Required arguments | Optional arguments | Result and limits |
| --- | --- | --- | --- |
| `method_references` | `projectId`, `className`, `methodName` | `methodDescriptor`, `direction`, `offset`, `limit` | Indexed direct `calls`; direction `callers` (default) or `callees` |
| `field_references` | `projectId`, `className`, `fieldName` | `fieldDescriptor`, `access`, `offset`, `limit` | Indexed `fields`; access `all` (default), `reads` or `writes` |
| `method_call_context` | `projectId`, `className`, `methodName`, `instructionIndex` | `methodDescriptor`, `argumentValues`, `fieldValues` | Receiver and actual arguments for one direct invocation, `calleeArgumentValues` |
| `recover_strings` | `projectId`, `className` | None | Bounded static `<clinit>()V` string recovery and ordered key evidence |
| `compare_structure` | `sourceProjectId`, `outputProjectId` | `classNames`, `memberNames`, `limit` | Source/output hashes, mapped structural differences and total count |

Reference pages expose `totalCount`, `offset`, `nextOffset`, `truncated` and
`scanComplete`. Default limit is 100, accepted range 1-1,000. Keep selectors
fixed while following `nextOffset`. Sites retain source descriptors, instruction
indexes and target descriptors. They do not resolve virtual dispatch, reflection,
bootstrap behavior or external callers. Field selection must resolve uniquely.

Call context accepts a raw ASM index identifying a direct `INVOKE*` instruction,
not invokedynamic. Its numeric `calleeArgumentValues` can seed a selected callee,
but describe one observed normal-flow site under recorded assumptions. Receiver
and unknown arguments stay unknown; omitted map entries are not zeros.

String recovery models a restricted cyclic-XOR char-array initializer as data;
it never loads target classes or invokes their methods. Inspect `status`:
`recovered` includes literal strings with field/array position, `cyclicKeys` in
order, source indexes and steps; `unsupported` includes diagnostics and no
speculative plaintext. Budgets include 10,000 instructions, 100,000 steps,
bounded arrays, four million allocated characters and one million returned
characters. Duplicate key values keep their original positions.

Structure maps have string values. `classNames` uses dotted source to output
names; `memberNames` uses exact `Class#method(descriptor)` or
`Class#field:descriptor` keys and new names. Descriptor references are remapped.
Collisions and constructor/initializer renaming are rejected. Checks cover
class access, superclass/interfaces, member access/descriptors and manifest
Main-Class. The returned `differenceCount` is independent of the bounded
`differences` list (default 100, cap 1,000). This tool does not compare method
bodies, resources, annotations or behavioral equivalence.

See [context and independent verification](ANALYSIS-AND-VERIFICATION.md) for
worked input conventions and separate artifact checks.

## Reconstruction

| Tool | Required arguments | Optional arguments | Result |
| --- | --- | --- | --- |
| `list_decompiler_backends` | None | None | ID/display name/version/status/notes |
| `decompile_class` | `projectId`, `className` | `backend` | Reconstructed class text |
| `decompile_method` | `projectId`, `className`, `methodName` | `methodDescriptor`, `backend` | Syntax extraction, possibly class fallback |

Backends are `cfr` (default) and `vineflower`. Results retain backend/version,
`inputSha256`, scope, source, warnings, failed regions, confidence and elapsed
time. No source-output checksum is returned. Discover actual backend versions.

Method extraction parses Java declarations and checks the complete erased JVM
signature against the selected class-file method. `emittedDescriptor` is the
verified JVM signature; `emittedDeclaration` contains the Java declaration text.
`exactMethodMatch` is true only for one matching declaration, including its
parameter and return types. Unresolved types, ambiguity and `<clinit>` requests
return full-class fallback with a false match and a failed-region diagnostic.
Backend failure markers, unstructured pseudocode and Java syntax errors are
reported in `failedRegions` and lower confidence. Parsing requires a JDK, does
not execute target code and does not establish body equivalence or compilation
against missing dependencies. Bytecode remains authoritative for semantics.

## Finders

| Tool | Category or result |
| --- | --- |
| `find_number_secrets` | `number` |
| `find_string_transforms` | `string-transform` |
| `find_opaque_predicates` | `opaque-guard` |
| `find_control_flow_flattening` | `flattening` |
| `find_invokedynamic_dispatch` | `invokedynamic` |
| `finder_catalog` | Category/subpattern vocabulary and tool mappings |
| `evidence_summary` | Ranked aggregate of requested categories |

Each requires `projectId` and accepts `limit`, `initialEvidenceId` and
`categoryFilter` (category-string array). No class/method filter is accepted.
`finder_catalog` needs no arguments and publishes vocabulary/tool mappings.
`evidence_summary` requires `projectId`, accepts `limit`/`categoryFilter`
and has no cursor.

Limits must be positive, default to 100 and cap at 1,000. They cap payload, not
scan depth. Results expose hash, `candidateCount`, `truncated`,
`scanComplete`, findings, summaries and warnings. Findings retain
ID/category/subpattern, method scope/indexes, evidence, confidence/weight,
limitations and next tools.

First-page counts describe the full filtered category result. A category
cursor excludes its ID and preceding results; counts/summaries then describe
the remaining suffix. Keep filters/identity fixed, save initial totals and
merge by exact method scope/indexes. Use the last returned ID for the next page, finishing when
`truncated` is false or reporting the declared bound. Exhaust a truncated
summary through individual category finders.
Current finding IDs omit method descriptors and can collide between overloads
with matching indexes. An ambiguous cursor may repeatedly select the first
occurrence. Detect no progress, restart without a cursor with a larger limit
up to 1,000, and retain distinct method records despite matching IDs. If still
capped, report incomplete collection; ID-only deduplication can lose findings.

Scores are heuristic rankings, not probabilities or maliciousness verdicts.
`switch-dispatcher` requires a switch controlled by a local selector that is
updated on a control-flow path back to the switch. A normal event switch alone
is not flattening. A static boolean traced through copies to at least three
boolean branches is reported as
`static-boolean-guard` under `opaque-guard`, with deadness explicitly unproven.
The legacy `dead-branch-guard` vocabulary remains discoverable for old clients
but is no longer emitted solely from branch/goto counts.
`constant-table-xor-decoder` does not provide plaintext. Ordinary lambdas and
string concatenation produce invokedynamic findings; bootstrap metadata is
not a resolved runtime call target.

## Guarded patching

| Tool | Outer arguments | Purpose |
| --- | --- | --- |
| `patch_catalog` | None | Engine/schema and six operations |
| `method_patch_draft` | `projectId`, `request`; optional `inputSha256` | Validate operations and return three fingerprints |
| `method_patch_preview` | `projectId`, `request`; optional `inputSha256` | Prepare/verify bytes and bind preview ID |
| `apply_method_patch` | `projectId`, `request`, `previewId`; optional `inputSha256` | Publish prepared bytes to new artifact/project |

Nested fields are class/method/descriptor, `schemaVersion`, `operations`,
three hashes and optional `idempotencyKey`. Preview/apply require hashes and
anchor opcodes. An outer hash must agree with the nested one. Indexes are
integers; operation argument values are strings.

See [method patching](METHOD-PATCHING.md) for construction, operations,
checks, lifetime and recovery. Draft prepares no bytes; preview can persist
bookkeeping but publishes no archive; apply publishes without changing source.
Apply currently returns `reindexed: false`. Index the output project and record
that postcheck separately before inspecting its requested change.

## Platform

| Tool | Arguments | Result |
| --- | --- | --- |
| `health` | None | Plain-text readiness/version and endpoint counts |
| `list_tools` | None | JSON domain definitions with schemas/envelopes |

MCP `ping`, `initialize`, `notifications/initialized`, `tools/list` and `tools/call` are protocol methods,
not additional callable analysis tools.
