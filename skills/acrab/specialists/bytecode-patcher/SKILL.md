---
name: acrab-bytecode-patcher
description: Prepare, verify and optionally apply a schema v2 Acrab method patch using source anchors, three identity hashes and a single-use preview bound to the exact request.
---

# Altair: Bytecode Patcher

Turn a concrete method change into a reviewable preview and, when authorized,
a new artifact/project. Read [the shared contract](../../workflows/common.md)
and [the patch workflow](../../workflows/bytecode-patcher.md).

## Intelligence requirement

Minimum **4/5**, recommended **4/5**, escalation **5/5** for complex control-flow
changes or uncertain application state. The main agent uses
[model routing](../../references/model-routing.md) for authorized delegation;
ratings do not authorize apply or substitute for preview/postchecks.

## Preferred tools

`patch_catalog`, `method_patch_draft`, `method_patch_preview`,
`apply_method_patch`; `verify_archive_identity`, `method_bytecode` and
`index_archive` supply identity, anchors and output checks.

## Gates that matter

- Establish change and stage. Preview does not authorize apply; preserve apply
  permission already given.
- Use exact class/method/descriptor and source indexes from `method_bytecode`.
  Jump targets must be existing `labelIndexes`. One request addresses one
  method and contains 1-50 supported operations.
- Draft must be supported/valid and each operation valid. Copy three hashes
  and returned anchor opcodes. Draft is not final verifier success.
- Review supported/valid preview, nonempty `previewId`, applied operations,
  `asm-static` diagnostics/checks and semantic/structural/debug changes.
  A truncated diff is not a complete change listing.
- Apply the identical request once while prepared bytes remain cached.
  IDs bind source/project/request/engine and class bytes. Expiry/restart needs
  preparation again; consumption needs existing-result recovery.
- Check output artifact/project and `verified`; record the raw `reindexed`
  flag and index the output explicitly when false. Check the requested change
  and reset instruction indexes for the new source.
- Recompute output identity and preserve any contextual assumptions used to
  justify the requested change.
  Static verification does not establish intended behavior.

## Boundaries

Signed JARs and target multi-release overrides are unsupported. Missing debug
tables or ambiguous anchors cannot be repaired by inventing entries. Never
bypass hashes, request binding or verifier checks. Preview may write idempotency
bookkeeping; only apply publishes patches. Static checks do not prove runtime
linking or semantic equivalence. Uncertain apply must not trigger blind retries
or a new idempotency key.
