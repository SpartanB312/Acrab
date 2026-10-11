# Altair Workflow: Draft, Review, Publish

Use with [Altair's skill](../specialists/bytecode-patcher/SKILL.md) and
[the shared contract](common.md). Tool discovery is the authority for request
shapes; the distributed method-patching manual supplies operation examples.

## Establish the change

Record source project/SHA-256, exact class/method/descriptor, desired change and
authorized stage: catalog, draft, preview or apply. An analysis result does not
authorize application. Prepare a concrete preview before seeking missing apply
authorization; use permission already supplied without asking again.

One request changes one method with 1-50 operations. Multiple method requests
must be sequenced through their output projects and re-prepared from each new
source; do not reuse earlier source indexes or hashes.

## Gate 1: Source and operation selection

Run `patch_catalog` and descriptor-selected `method_bytecode`. Copy observed
source indexes/opcodes; for jump replacement, use an existing `labelIndexes`
value as `targetInstructionIndex`. Owners use internal JVM names such as
`java/lang/String`, while class selection uses the class name from inventory.

Choose a supported operation whose effect matches the request. Debug edits need
existing, unambiguous tables; operations sharing an anchor can conflict. Do not
guess an anchor outside a truncated listing or infer dead code from a finder.

## Gate 2: Draft

Call `method_patch_draft` with outer `projectId` and nested `request`:
class/method/descriptor, `schemaVersion: 2` and operations. Operation argument
values are strings, even for numeric and boolean fields. Draft can omit hashes
and anchor opcodes to obtain canonical source values.

Require `supported: true`, `status: valid`, every operation `valid` and no
failure. Inspect `patchMetadataAvailability`. Copy `inputSha256`,
`methodInstructionSha256`, `debugMetadataSha256` and every returned
`anchorOpcode` into the same request. If the intended operations change,
draft them again; fingerprints alone do not validate the new operation set.

## Gate 3: Preview and review

Call `method_patch_preview` with the completed request. Require:

- same project, source hash, exact method and source method/debug fingerprints;
- `supported: true`, `status: valid`, a nonempty `previewId` and every
  operation `applied`;
- `verificationScope: asm-static`, checks passed and diagnostics reviewed;
- before/after operation evidence implementing the requested change;
- `changedInstructionCount == instructionChanges.length` and semantic plus
  structural counts equaling that total; debug changes inspected separately.

Inspect `patchedClassSha256`, patched method/debug hashes and warnings.
When `diffTruncated` is true, operation evidence replaces the full diff.
If full change review is required, do not claim it passed on this smaller view.

Preview does not publish an archive, but may persist idempotency bookkeeping.
Report a successful preview as preview completion, not patch publication.
Do not claim intended behavior from static verification.

## Gate 4: Apply and postcheck

Apply only when authorized. Send the exact nested request used for preview
plus outer `projectId` and `previewId` to `apply_method_patch`.
Do not change `idempotencyKey`, operations, hashes, opcodes or arguments.

Check source identity/preview ID, `artifactPath`, `artifactChecksum`,
`outputProjectId`, `verified: true`, changed methods/classes and verification
scope. Record `reindexed`; the current implementation returns false and does
not automatically index its output. Call `index_archive` when needed and verify
its output checksum. Obtain output `archive_summary`; its hash must equal
`artifactChecksum`. Inspect the requested method/change on the output project.
Run `verify_archive_identity` with `artifactChecksum`. Preserve any caller/field
assumptions used to justify the change. Static verifier success does not prove
behavioral equivalence.
Changed indexes now belong to a new source. A failure of postchecks means
partial completion even when an artifact was published.

## Expiry, failure and uncertain application

Prepared bytes are process-local: 15-minute TTL, 64 entries, 128 MiB total.
Expiry, eviction or restart loses preparation. If application was never sent,
draft/preview the same current source again. Keep the reviewed request stable.
An idempotency key cannot be rebound to a different source/request.

A consumed preview cannot publish again, even after restart. On
`preview-consumed`, recover the output project ID in the diagnostic and
inspect that project; do not re-preview or change keys to duplicate publication.

After timeout/disconnect during apply, the outcome is unknown. Inspect
`list_projects` and any returned output ID/artifact metadata to reconcile it.
There is no dedicated public journal-status endpoint. If matching output
cannot be identified unambiguously, report the uncertainty and stop applying.
Do not treat absence from a single project listing as proof that no apply ran.

On hash/request mismatch, unsupported archive/debug metadata, unresolved apply
state or verifier failure, stop that request and correct the stated precondition.
Signed JARs and target multi-release overrides are unsupported. Missing hierarchy
dependencies can fail verification. Never edit journals/tokens or weaken checks.

## Completion

Catalog/draft/preview tasks complete at their requested gate. Apply completes
only after publication identity and requested-change postchecks. Return exact
source/output identity, completed stage, request/preview evidence, verification
limits and artifacts through the shared contract.
