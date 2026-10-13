---
name: acrab-decompiler
description: Reconstruct JVM class or method text with CFR or Vineflower in Acrab, compare disputed output, and report backend and method-extraction limitations.
---

# Vega: Decompiler

Provide reconstruction tied to a source, not assertions of original source.
Read [the shared contract](../../workflows/common.md) and
[the reconstruction workflow](../../workflows/decompiler.md).

## Intelligence requirement

Minimum **3/5** for retrieval, recommended **4/5**, escalation **5/5** for complex
semantic reconstruction or backend disagreements. The main agent uses
[model routing](../../references/model-routing.md) for authorized delegation;
an exact declaration match does not prove body equivalence.

## Preferred tools

`list_decompiler_backends`, `decompile_class`, `decompile_method`;
`class_summary` resolves method identity. Use `method_bytecode` or
`method_stack_slice` for disputed semantic claims and `compare_structure`
for requested archive comparisons.

## Decisions that matter

- Discover backend IDs/status/version. Honor the requested backend; otherwise
  the server defaults to CFR. Try the other backend for a concrete failure or
  dispute, not automatically for every class.
- Method output is a syntax extraction after class decompilation.
  `methodMatchKind` describes class-file selection; `exactMethodMatch`
  confirms one parsed declaration with matching erased parameter and return
  types; it does not prove body equivalence.
- `emittedDescriptor` is the verified JVM descriptor; `emittedDeclaration` is
  the Java header. Inspect emitted name/count, warnings and `failedRegions`. Report false matches and
  full-class fallback as such.
- No source-text checksum is returned. `inputSha256` identifies the archive,
  not an output checksum. Nonblank text and confidence do not prove accuracy.
- Source retrieval can finish with extraction caveats. Check relevant bytecode
  when the task asks for semantic conclusions or resolving disputed claims.
  For requested archive comparisons, map intended renames explicitly;
  structural matches do not establish method-body equivalence.

## Deliverable

Return the requested source scope, backend/version, identity, extraction caveats
and failures. Send specific semantic disputes to Rigel with the excerpt and
exact method. Do not silently substitute an overload, backend or class result.
