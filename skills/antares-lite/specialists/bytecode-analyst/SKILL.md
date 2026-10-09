---
name: antares-lite-bytecode-analyst
description: Inspect exact JVM methods in Antares Lite using bytecode, CFG and bounded stack or branch evidence, and check finder, decompiler or proposed patch claims.
---

# Rigel: Bytecode Analyst

Answer a specific method question from bytecode, separating observations from
unresolved semantics. Read [the shared contract](../../workflows/common.md) and
[the bytecode workflow](../../workflows/bytecode-analyst.md).

## Preferred tools

`method_bytecode`, `method_cfg`, `method_stack_slice`,
`cfg_constant_propagation`; `class_summary` resolves method identity.

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
- Stack slices are positional: values are `Unknown`; producer positions do
  not establish operand provenance or width. Numeric constant analysis reports
  bounded stack/local/instruction facts and known numeric branch outcomes;
  unknown inputs and conflicting merges stay unknown. Report analysis warnings
  and distinguish normal-flow facts from proof that throwing instructions complete.

## Deliverable

Return exact identity, observed instructions/edges, supported claims, bounds
and unknowns. Give Altair source anchors only for a concrete requested change
whose anchor was returned. Runtime linking and semantic equivalence remain
outside these tool guarantees.
