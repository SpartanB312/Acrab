# Tool Reference

This reference describes the bundled 1.4 server. Use runtime `tools/list` or
generated `catalog/tools-catalog.json` for full schemas. There are 54 domain
tools plus `health` and `list_tools`; registry "analysis tools" include import
patching and Android artifact creation/import.

## Call and decode

Use MCP `tools/call` with `params.name` and `params.arguments`. Most tools
use flat arguments; method patch tools use a nested `request`. Unknown keys
and wrong primitive types are rejected.

Results contain `result.content[].text` and `result.isError`. Most text
bodies contain JSON; `health` returns plain text. Check JSON-RPC `error` and
tool `isError`, then domain status/identity/coverage. HTTP success does not
establish tool or patch success.

## Project and retrieval

`create_project` accepts JVM archives, DEX/APK/AAB/multidex ZIP and manifest XML.
The indexing/retrieval tools in this section are JVM-JAR tools. Use the Android
tools below for Android projects; importing bytes does not validate them.

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

## Android tools

All project tools below require `projectId` and verify current stored bytes.
They do not use the JVM index. Required extra parameters are named explicitly;
the other listed parameters are optional. See [Android analysis](ANDROID.md)
for formats, configuration, actual output status fields and authorization.

| Tool | Extra arguments | Result and bounds |
| --- | --- | --- |
| `android_dex_summary` | None | Hash, parser, DEX entries and structural counts |
| `android_list_classes` | `query`, `dexFile`, `limit` | Class array; default 100, range 1-500 |
| `android_class_summary` | Required `className`; optional `dexFile` | Unambiguous class and exact method descriptors |
| `android_search_strings` | `query`, `dexFile`, `limit` | Static references; default 100, range 1-500 |
| `android_search_invocations` | `owner`, `name`, `dexFile`, `limit` | Static calls with source/target descriptors; default 100, range 1-500 |
| `android_method_bytecode` | Required `className`, `methodName`; optional `descriptor`, `dexFile`, `limit` | Exact DEX evidence, hash and truncation; default 500, range 1-10,000 |
| `android_find_sensitive_apis` | `limit` | Candidate count, findings, limitations and truncation; default 200, range 1-500 |
| `android_manifest_summary` | None | Plain/AXML/protobuf observations, module identity and warnings |
| `android_manifest_raw` | `maxBytes` | UTF-8 or Base64 source with encoding and truncation; default 1,000,000, range 1-4,194,304 |
| `android_manifest_findings` | None | Manifest hints, not exploitability conclusions |
| `android_dex2jar_convert` | None | Validated separate JAR through trusted dex2jar; source entry/command coverage |
| `android_baksmali_convert` | None | Validated smali ZIP through trusted baksmali, counts and local sample files |
| `android_asm_verify` | None | JVM archive class/method counts, issues and truncation; no runtime proof |
| `android_dex_recompute_checksum` | None | Separate DEX/archive, count and output paths/checksums |
| `android_smali_assemble` | Required `inputPath` | Project-local smali tree to validated separate DEX |
| `android_jar2dex_convert` | None | D8/dx artifact, `outputExists`, DEX format/coverage, min API and version |
| `android_jar2jasmin_convert` | None | Explicit unsupported/unavailable result, no artifact |
| `android_jasmin2jar_convert` | Required `inputPath` | Project-local classic Jasmin to validated JAR, class count and command |
| `android_class_version_switch` | Required `targetMajorVersion` (45-65) | Separate JVM JAR with header changes only |
| `import_android_artifact` | Required `artifactPath`; optional `expectedSha256` | Project-owned derived artifact copied into a new project record |

`android_dex2jar_status` and `android_dex_tools_catalog` take **no arguments or
project**. They inspect bundled/external availability and capability contracts, not
successful conversion. The Android surface contains 21 ported tools and one
Acrab-specific artifact-import tool. Search arrays have no cursor/total; a
full-sized result may be capped. Inspect actual schemas before calling.

Installed defaults include dex2jar 2.4.38, smali/baksmali 3.0.10, D8/R8 9.5.23
and classic Jasmin 3.0.3. They run using the current JDK with allowlisted main
classes and checksum-verified isolated classpaths, without downloads or helper
scripts. Optional external roots disable bundled fallback. Jar-to-Jasmin remains
unsupported even though the assembler is bundled.

## Platform

| Tool | Arguments | Result |
| --- | --- | --- |
| `health` | None | Plain-text readiness/version and endpoint counts |
| `list_tools` | None | JSON domain definitions with schemas/envelopes |

MCP `ping`, `initialize`, `notifications/initialized`, `tools/list` and `tools/call` are protocol methods,
not additional callable analysis tools.
