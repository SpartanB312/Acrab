# Rigel Workflow: Check a Method Claim

Use with [Rigel's skill](../specialists/bytecode-analyst/SKILL.md) and
[the shared contract](common.md).

## Input and question

Require established source identity and a concrete method claim, branch,
instruction or candidate to inspect. If the method descriptor is missing,
retrieve `class_summary` and select from its descriptors; do not guess an
overload. Discovery may finish with an explicit ambiguity if user intent
cannot select one.

## Procedure and checks

1. **Address.** Send `projectId`, `className`, `methodName` and
   `methodDescriptor` to `method_bytecode`. Check returned class/method,
   `descriptor`, `requestedDescriptor`, `methodMatchKind: DESCRIPTOR`
   and `exactMethodMatch: true` before using a method claim.
2. **Locate.** Find the relevant returned `index`, opcode and operand.
   Preserve raw ASM index gaps, branch `jumpTargetIndex`, `switchTargets`
   and `labelIndexes`. `instructionCount` counts metadata nodes too and
   therefore need not equal the executable listing length.
3. **Choose evidence.** Use only the tools needed for the question:
   - `method_cfg` for block boundaries, static edges and modeled reachability;
   - `method_stack_slice` with the observed `instructionIndex` and, when
     relevant, `argumentSlots` for actual consumed operands;
   - `cfg_constant_propagation` for block/branch operands and destinations;
   - `method_references`, `field_references` and `method_call_context` for
     indexed callers, guard writers and one observed callsite;
   - `recover_strings` for supported cyclic-XOR initializers;
   - an existing finder record or reconstructed excerpt for a cross-check.
4. **Check bounds.** Bytecode caps executable output at 500; CFG/constant
   blocks cap at 100. Inspect `truncated`; there is no paging parameter.
   Under truncated CFG output, some `unreachableBlocks` are omitted blocks
   and edges can point outside the returned block set. Do not classify these
   as proven dead code.
5. **Evaluate the claim.** Link each assertion to actual consumed values and
   destinations, preserving `producerInstructionIndexes`, origins, parameter
   locals and JVM widths. Operands are bottom-to-top; a singular producer is
   supplied only when unique. Check `analysisStatus`, provenance truncation
   and warnings. Numeric facts expose `taken`, `not-taken` or `unknown`, the
   condition expression, operands, target/fallthrough indexes and unknown reason.
   Unknown inputs and conflicting merges remain unknown. An unknown result
   cannot authorize branch removal; normal-flow facts do not prove that throwing
   operations complete.
6. **Return or hand off.** Report the observed condition/call/constant, the
   model's bounds and unresolved assumptions. Give Vega a disputed bytecode
   excerpt or Altair an observed source anchor plus the intended change.

## Conflicting and missing evidence

Check project/checksum and descriptors before comparing expert output.
A decompiled loop or condition is reconstruction; use its exact disputed
operation to locate source bytecode. Agreement between two decompilers still
does not establish runtime behavior.

For an argument-dependent guard, page exact method callers, locate the direct
invocation and use `method_call_context`. Its `calleeArgumentValues` can become
`cfg_constant_propagation.argumentValues` in the callee. Keys are zero-based
formal argument ordinals, excluding `this`, not JVM local slots. Values are
numeric strings. Field assumptions use internal `owner/name.field:descriptor`
keys with string values. Record the source callsite/write and maps; results
are conditional on supplied assumptions, not all callers or a global invariant.
Unknown call arguments are absent, not invented. Reference scans do not resolve
reflection, virtual dispatch, external dependencies or bootstrap execution.

For loops, check initialization, counter test, decrement and side-effect edges
separately. Trace consecutive branches individually; the nearest instruction
is not necessarily either branch's consumed producer. Retain differences
between arbitrary long-key arguments and the observed game-loop argument.

For static strings, require supported recovery status and inspect ordered
cyclic keys, returned literals, source indexes and diagnostics. Unsupported
patterns remain unknown and never trigger target initializer/decoder execution.

An abstract/native method can have no body; report that fact rather than
inventing a CFG. If a required anchor is beyond the listing cap, do not count
forward or take an index from an edited listing. State the unavailable evidence
or use a separately authorized inspection method with explicit provenance.

## Completion

Complete when the requested bounded question is answered or its unsupported
part is clearly identified. Return partial if the requested coverage depends on
omitted instructions/blocks. Record static-model reachability as such, preserve
warnings, and distinguish known normal-flow numeric branch facts from runtime
completion. Operand provenance is bounded model evidence; full runtime linking
and semantic equivalence remain outside the tool guarantee.
