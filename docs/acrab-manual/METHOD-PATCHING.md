# Guarded Method Patching

Schema v2 patches one exact method per request, with 1-50 operations. Draft
validates anchors; preview prepares/verifies class bytes; apply publishes those
bytes once to a new artifact/project. The input JAR is unchanged.

Read `patch_catalog` and current `tools/list` schemas. Catalog
`proofRequired: false` means no proof-token argument is required; it does
not certify semantic correctness.

## Stages and checks

| Stage | Required evidence | Completion means |
| --- | --- | --- |
| Source | Project/hash, exact method, observed anchors | Source and requested change are unambiguous |
| Draft | Supported/valid, every operation valid, metadata available | Request validates; final class not yet verified |
| Preview | Supported/valid, applied operations, ID, reviewed diff/static checks | Prepared reviewable output exists in this process |
| Apply | Authorized identical request, live unconsumed preview | New artifact/project published once |
| Postcheck | Verified output hash, successful output indexing, observed change | Published output matches the bounded requested change |

Preview-only work finishes without apply. Preserve existing permission for the
concrete change. When apply permission is missing, prepare the reviewable
preview before asking for the final decision.

## Operations and types

Anchors are executable source indexes from `method_bytecode`. Gaps are
expected because omitted label/line/frame nodes occupy raw ASM positions.
Metadata nodes cannot be operation anchors.

| Operation | Required arguments | Optional arguments | Preconditions |
| --- | --- | --- | --- |
| `replace_insn` | `kind`, `opcode`, kind-specific fields | Kind-dependent | Supported opcode/operands; final class verifies |
| `delete_insn` | None | None | Removes one executable anchor; final class verifies |
| `replace_constant` | `value` | `valueType` (`string`/`int`) | String/Int `LDC`, same type |
| `redirect_call` | `owner`, `name`, `descriptor` | `itf` | Existing method invocation; preserve required stack shape |
| `patch_local_name` | `name` | `descriptor` | Existing table, exactly one active entry for anchored slot |
| `patch_line_table` | `line` | None | Existing unambiguous line entry; range 0-65535 |

Replacement kinds: `simple`, `int`, `var`, `ldc`, `type`, `field`,
`method`, `jump`. The int/var/ldc kinds require `operand`; type requires
`typeDescriptor`; field/method require `owner`, `name`, `descriptor`
(method also accepts `itf`); jump requires `targetInstructionIndex` from
existing `method_bytecode.labelIndexes`. Kind schemas and draft validation
govern opcode/value choices; this is not an arbitrary ASM editor.

`anchorInstructionIndex` is an integer. Values inside `arguments` are
strings, including `"line": "42"`, `"itf": "false"` and
`"targetInstructionIndex": "17"`. Owners use internal names such as
`java/lang/String`. Local `descriptor` filters an existing entry, not a
method signature edit.

Operations always refer to original source nodes. Conflicting anchors/metadata
edits can fail draft. A subsequent patch must use the previous output as its
source when composition is intended, with fresh indexes/hashes.

## Build a request from evidence

Use the existing MCP connection described in [the quickstart](USER-QUICKSTART.md).
HTTP tool calls return SSE; a plain JSON-only HTTP helper cannot decode those
responses. Build the request from the selected imported archive and observed
method.

1. Verify the source project and checksum, resolve the exact method descriptor,
   and inspect `method_bytecode` plus `patch_catalog`.
2. Select executable anchors from the returned source instruction indexes.
   Require observed opcode/operands and complete evidence for the intended
   change; do not guess indexes or count visible lines.
3. Call `method_patch_draft` with outer `projectId` and a nested `request`
   containing `schemaVersion: 2`, `className`, `methodName`,
   `methodDescriptor` and `operations`. Operation argument values are strings.
   Require `supported: true`, `status: valid` and every operation `valid`.
4. Copy draft `inputSha256`, `methodInstructionSha256`,
   `debugMetadataSha256` and each operation's `anchorOpcode` into that same
   request. Call `method_patch_preview` with it; require a valid supported
   preview and a nonempty `previewId`.

Review every operation's status/evidence, source fingerprints,
`patchedClassSha256`, diagnostics/checks, semantic/structural/debug changes
and warnings. Require `verificationScope: asm-static`. The changed instruction
count equals `instructionChanges.length` and semantic plus structural counts.
Debug changes are separate. With `diffTruncated`, evidence is operation-level,
not a full diff.

Only after review and authorization, call `apply_method_patch` with the same
outer `projectId`, the returned `previewId` and the identical nested request.
Check `verified`, output project ID, artifact path and checksum. The current
implementation returns `reindexed: false`; index the output when needed,
compare its summary/index checksum with `artifactChecksum`, run
`verify_archive_identity` and inspect the requested method change on the
output project. Record the raw apply flag separately from later indexing
success. Output instruction indexes belong to that new source.

Keep original identity, preview evidence, output path/hash/ID and the bounded
verification/postchecks. This workflow never executes the target. A timeout,
cancellation or disconnected response does not roll back import, indexing or
patch work; resolve uncertain publication as described below.

## Lifetime and recovery

IDs bind project/archive, canonical request, method/debug fingerprints, class
bytes and engine/schema. Optional `idempotencyKey` cannot be rebound to a
different request/source; keep it identical through preview/apply.

Prepared bytes live in process memory: 15 minutes, 64 entries and 128 MiB total.
Other requests can evict them. Restart loses preparation while durable
consumption/idempotency bookkeeping can remain. Consumed requests cannot mint
replacement tokens to publish again.

| Condition | Action |
| --- | --- |
| Invalid/unsupported draft | Correct the stated operation/anchor/metadata condition; draft again |
| Failed verifier or missing required dependency | Resolve the limitation; do not apply or weaken checks |
| Hash/opcode/request mismatch | Re-establish identity/intent; do not blindly replace expected hashes |
| Expired/evicted/restarted preview, apply never sent | Re-draft/re-preview that source and review again |
| `preview-consumed` | Recover the output ID in the diagnostic; inspect that output |
| Timeout/disconnect during apply | Reconcile output with `list_projects`, known output IDs and artifact metadata |
| Unresolved apply state | Stop application and report uncertainty; no public journal-status tool exists |

Do not blindly retry uncertain apply, change keys to duplicate publication, or
treat a single missing listing as proof that no apply ran. If output cannot be
identified unambiguously, report the missing outcome as partial/blocked.

## Verification and unsupported input

`asm-static` checks serialized structure/types/stacks without target execution.
Missing hierarchy dependencies can fail required checks. Success does not prove
runtime linking, access resolution in deployment, behavior or equivalence.

Signed JARs and multi-release overrides of the target class are unsupported;
do not strip them silently. Debug edits require existing unambiguous entries.
Oversized, duplicate or invalid archive structures can fail validation.
Schema v1/old tokens do not migrate implicitly: start a v2 draft from current
source evidence.
