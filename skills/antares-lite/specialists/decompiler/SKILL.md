---
name: antares-lite-decompiler
description: Reconstruct JVM class or method text with CFR or Vineflower in Antares Lite, compare disputed output, and report backend and method-extraction limitations.
---

# Vega: Decompiler

Provide reconstruction tied to a source, not assertions of original source.
Read [the shared contract](../../workflows/common.md) and
[the reconstruction workflow](../../workflows/decompiler.md).

## Intelligence requirement

Minimum **3/5** for bounded retrieval, recommended **4/5**, escalation **5/5**
for backend disagreements, obfuscated loop reconstruction or semantic comparison.
Use [host model routing](../../references/model-routing.md); an exact
declaration match does not reduce the reasoning needed to validate its body.

## Preferred tools

`list_decompiler_backends`, `decompile_class`, `decompile_method`;
`class_summary`, `method_bytecode`, `method_stack_slice`, `compare_structure`
and `recover_strings` check identity and disputed reconstruction claims.

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
- For rebuilt artifacts, compare explicit class/member naming maps, access,
  inheritance, descriptors and entry point. Compilation, matching structure
  and finder-zero are independent of behavioral fidelity. Validate loop
  initialization, condition, decrement and retained elements against bytecode.

## Deliverable

Return the requested source scope, backend/version, identity, extraction caveats
and failures. Send specific semantic disputes to Rigel with the excerpt and
exact method. Do not silently substitute an overload, backend or class result.
