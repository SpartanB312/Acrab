# Shared Specialist Contract

These are shared domain rules and an optional agent handoff format, not an MCP
request schema. Pair them with the selected specialist skill and workflow.
Local work needs no formal handoff object. Read their files when accessible;
otherwise the coordinator supplies the text. Do not demand inline copies when
resources can be read. Obtain inaccessible instructions before domain work
rather than inventing a substitute workflow.

Use the parts of a role workflow that answer the request. Analysis tools are
options, not mandatory stages; patch preparation and publication retain their
fixed safety gates.

## Task input

Supply the user goal, deliverable, exact scope, source identity, existing
evidence, authorization and a stop condition. Method scope includes class,
method and JVM descriptor. Discovery scope includes queries/categories and a
result budget. Unknown inputs may be null; Sirius establishes identity and
`class_summary` can resolve an ambiguous descriptor before deep analysis.

```json
{
  "goal": "Explain the branch at the selected source instruction",
  "deliverable": "Static evidence with unresolved assumptions",
  "projectId": null,
  "inputSha256": null,
  "scope": {
    "className": "example.Guard",
    "methodName": "check",
    "methodDescriptor": "(I)Z",
    "instructionIndexes": []
  },
  "authorization": { "applyPatch": false },
  "evidence": [],
  "stopCondition": "Answer this branch question or identify the missing evidence"
}
```

Use the existing MCP connection. The coordinator owns transport setup. The
server supports newline-delimited stdio and Streamable HTTP `POST /mcp` with
JSON discovery/listings and request-scoped SSE tool responses. Let the MCP
client handle framing and protocol negotiation. `2026-07-28` uses per-request
metadata and matching HTTP headers without sessions. Supported 2025 clients
initialize without a session header, retain the server-issued session ID and
send `notifications/initialized` before tool requests. Legacy notifications
return 202 with no body; unknown/expired sessions require a fresh handshake.
Streams are not resumable. A timeout or disconnect can leave import, indexing
or patch work running; reconcile uncertain writes instead of blindly retrying.

## Source identity and addressing

- Preserve project and SHA-256 together. Project records call the hash
  `sha256`; index/summary use `archiveSha256`; finder, decompiler and patch
  use `inputSha256`. Normalize these into handoff `inputSha256` without
  inventing a server field.
- Obtain `archive_summary` when inventory is needed; reuse it instead of
  importing and indexing for every expert. Stored archives and index caches are not live
  views of externally edited files. Import changed bytes as a new project.
- Use `verify_archive_identity` to recompute current stored bytes, comparing
  `recordedSha256`, `actualSha256`, `matches` and optional `expectedMatches`.
  Index reuse verifies current bytes and rejects mismatch. Compare raw response
  fields before claiming identity conflict; an earlier report or filename is
  insufficient. Save `inputSha256` and `analysisVersion` on method evidence.
  Reuse verified identity within the same task while the stored bytes remain
  unchanged; verify again when adopting a different input or output project.
- Copy class names/descriptors from `class_summary`. Bytecode responses use
  `descriptor`; decompile/patch use `methodDescriptor`. Requests consistently
  use `methodDescriptor`.
- Name-only lookup is allowed only when unambiguous. Before method-specific
  claims or patch anchors, repeat with the exact descriptor. Bytecode tools
  then report `methodMatchKind: DESCRIPTOR` and `exactMethodMatch: true`.
  Decompiler flags confirm parsed declaration signatures, not body equivalence;
  follow Vega's workflow for extraction failures and backend diagnostics.
- Indexes are raw ASM instruction-list positions. Visible bytecode omits
  label/line/frame nodes, so indexes have gaps. Use returned `index`,
  `labelIndexes` and targets; never renumber a listing.

## Evidence and coverage

| Level | Meaning | Qualification |
| --- | --- | --- |
| `fact` | Observed archive, index, instruction or backend property | Tool/response location; limit the claim to the observation |
| `candidate` | Detector signal or unconfirmed static hypothesis | Confidence, assumptions and confirmation needed |
| `reconstruction` | Decompiled source or syntax extraction | Backend/version, warnings, failed regions and match caveats |
| `unknown` | Evidence cannot resolve the claim | Missing evidence or unsupported capability |

Preserve provenance: tool, arguments/scope, response pointer, exact method,
instruction/finding IDs and warnings. Inspect `isError`, domain status,
identity and truncation before using a result. A fact about a tool response is
not automatically a fact about runtime behavior.

Numeric argument and field bindings are explicit assumptions. Preserve their
maps, originating callsite/write and conditional scope. One observed direct
callsite does not establish all callers, dynamic dispatch or a global field
invariant. Producer sets describe actual modeled operands, retaining merge
alternatives, widths, truncation and unknown values.

Separate scan coverage, returned payload and investigation coverage. Record
caps, returned count, total/remaining count, cursor and truncation when exposed;
use null otherwise. `scanComplete: true` does not make a truncated page
complete. Only reviewed findings support investigation conclusions. Absence
of signals is not absence of obfuscation.

## Authorization and effects

Import stores a copy/record; indexing caches data; decompilation can create
temporary files. Draft validates without preparing output. Preview prepares
bytes and may update durable idempotency bookkeeping, but publishes no patched
archive. Apply writes a new artifact/project and leaves the source unchanged.

Respect the requested stage. Preview-only work can finish without apply.
Apply requires authorization for the concrete change; preserve authorization
already given and do not ask again solely because a workflow has a review
gate. When authorization is absent, finish a reviewable preview before asking
for the final decision. Analysis and finder recommendations do not authorize
patching.

Never execute target code or manually change workspace archives, indexes,
tokens or journals. `asm-static` does not prove runtime linking, intended
behavior or semantic equivalence.

## Result handoff

Use structured JSON for delegated work; direct answers may use prose with the
same relevant information. This example is a shape, not a server response:

```json
{
  "expert": "acrab-bytecode-analyst",
  "status": "partial",
  "completedStage": "bytecode-inspection",
  "projectId": null,
  "inputSha256": null,
  "scope": { "className": "example.Guard", "methodName": "check", "methodDescriptor": "(I)Z" },
  "toolsUsed": ["method_bytecode"],
  "evidence": [],
  "claims": [],
  "coverage": { "requested": "one branch", "reviewed": [], "truncated": null },
  "artifacts": [],
  "limitations": ["Example only; no archive has been inspected"],
  "nextAction": { "expert": "acrab-jvm-indexer", "reason": "Establish source identity" }
}
```

Each real claim has `level`, `claim` and `evidenceIds` pointing to evidence
records. Evidence records identify tool, arguments, source identity and response
location. Candidate records retain ID, category, subpattern, confidence, weight,
limitations and next tools. Reconstruction retains backend/version and extraction
metadata. Artifacts identify source/output projects, path, checksum and checks.

`complete` means the requested deliverable and checks are satisfied; a bounded
answer may contain explicit unknowns. `partial` means useful evidence exists
but requested coverage or a required check is missing. `blocked` means no next
useful step is possible with available input, tools and authorization. Explain
the missing condition. A skipped step never counts as a passed check.

## Recovery and stopping

Correct schema errors from `tools/list`, then retry the corrected call. On
descriptor ambiguity, retrieve descriptors rather than trying random values.
On checksum disagreement, stop combining results and re-establish identity.
Capability questions can finish without a JAR.

Bound read-only retries by a concrete alternative, such as a narrower query or
the other supported backend. Do not repeat unchanged failing calls. Handle
patch expiry, consumption and uncertain application through Altair's recovery
flow; application is not a blind retry operation.
