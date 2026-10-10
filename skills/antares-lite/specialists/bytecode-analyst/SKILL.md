---
name: antares-lite-bytecode-analyst
description: Inspect exact JVM methods in Antares Lite using bytecode, CFG and bounded stack or branch evidence, and check finder, decompiler or proposed patch claims.
---

# Rigel: Bytecode Analyst

Answer a specific method question from bytecode, separating observations from
unresolved semantics. Read [the shared contract](../../workflows/common.md) and
[the bytecode workflow](../../workflows/bytecode-analyst.md).

## Intelligence requirement

Minimum **4/5**, recommended **4/5**, escalation **5/5** for irreducible loops,
merged operands or cross-call guard assumptions. Use
[host model routing](../../references/model-routing.md); ratings do not
replace bytecode evidence or authorize delegation.

## Preferred tools

`method_bytecode`, `method_cfg`, `method_stack_slice`,
`cfg_constant_propagation`, `method_references`, `field_references`,
`method_call_context`, `recover_strings`; `class_summary` resolves method identity.

## Decisions that matter

- Supply the exact descriptor before attaching claims or anchors to a method.
  Check returned `descriptor`, `requestedDescriptor`, `methodMatchKind`
  and `exactMethodMatch`.
- Bytecode returns at most 500 executable instructions; raw ASM indexes include
  omitted metadata nodes. CFG returns at most 100 blocks. Neither tool has
  an offset for paging large methods.
- CFG reachability is static model evidence. When truncated, the current
  `unreachableBlocks` also includes omitted blocks; it cannot justify deleting
  them. Edges may refer to blocks absent from the bounded payload.
- Stack slices trace actual consumed values bottom-to-top, with JVM width,
  immediate producer sets, origins and parameter locals. Retain merged alternatives;
  a null unique producer is not missing evidence when multiple producers exist.
  Inspect analysis status, warnings and provenance truncation.
- Branch facts include operands, expression, taken/fallthrough destinations
  and an unknown reason. Numeric facts preserve unknowns and conflicting merges.
  Supplied argument/field values are conditional assumptions, not global proofs.
  Distinguish normal-flow facts from proof that throwing instructions complete.
- Trace guard writers and exact callsite arguments before deriving a context.
  Static string recovery only supports bounded modeled initializers; retain
  key order, plaintext, source indexes, status and diagnostics. Never run decoders.

## Deliverable

Return exact identity, observed instructions/edges, supported claims, bounds
and unknowns. Give Altair source anchors only for a concrete requested change
whose anchor was returned. Runtime linking and semantic equivalence remain
outside these tool guarantees.
