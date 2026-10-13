# Vega Workflow: Reconstruct and Qualify

Use with [Vega's skill](../specialists/decompiler/SKILL.md) and
[the shared contract](common.md).

## Input and backend choice

Establish source identity and class or exact method scope. A backend capability
question uses `list_decompiler_backends` without a project. Otherwise discover
backend status/version once per connection, preserve an explicit user choice,
and use `cfr` by default when no preference exists.

## Procedure and checks

1. **Select scope.** Confirm the class and, for method work, descriptor from
   `class_summary`. Use `decompile_class` for class text or
   `decompile_method` with `methodDescriptor` for a method. The latter
   decompiles a class before extracting text; it is not a separate method engine.
2. **Inspect result.** Verify `projectId`, `inputSha256`, class, scope and
   requested backend/version. Record source, `warnings`, `failedRegions`,
   `confidence` and `elapsedMs`. Blank source or relevant failed regions
   means the requested reconstruction has not succeeded completely.
3. **Check method extraction.** Preserve `requestedDescriptor`,
   `methodDescriptor`, `methodMatchKind`, `exactMethodMatch`,
   `emittedMethodName`, `emittedDescriptor`, `emittedDeclaration` and
   `emittedParameterCount`. Extraction checks parsed parameter and return types
   against the complete JVM descriptor. The emitted descriptor is a JVM
   signature, and the emitted declaration is source text. A true match confirms
   declaration identity, not correctness of the reconstructed body.
4. **Handle fallback.** Static initializer extraction and unlocatable or
   mismatched declarations can return full class text with a false match.
   Label it as class fallback, preserve the warning, and do not present the
   entire class as the requested method. Inspect bytecode for the exact method.
5. **Resolve a concrete problem.** On unreadable/failed output or a disputed
   region, try the other available backend if that fits the task. Preserve
   both results and the specific difference. For same-arity overloads or
   semantic claims, cross-check `method_bytecode` or hand the exact claim
   to Rigel. Backend agreement is corroboration, not proof.
6. **Deliver.** Label all source as reconstruction. Preserve archive hash;
   the server returns no source-output checksum. Do not fabricate a hash or
   claim Kotlin/Java source recovery from Java-like output.

## Retry and completion

When structural comparison is requested, use the second supplied archive as a
separate project, verify both identities and call `compare_structure` with
explicit maps for intended class/member renames. This compares access, inheritance,
descriptors and entry point, not method bodies or behavior.

If the requested backend is unavailable or fails, report that and name any
fallback backend explicitly. Try at most the other supported backend for the
same concrete issue; repeating identical calls only revisits the cache or
same failure. Do not silently expand a method request into all classes.

Complete when the requested scope is reconstructed and limitations/method
ambiguities are disclosed. Return partial if a required region is missing or
a requested exact method cannot be isolated. A readable excerpt may answer a
bounded explanatory question while runtime semantics remain unknown.
