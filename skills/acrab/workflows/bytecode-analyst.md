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
     questions that depend on indexed callers, field access or a callsite;
   - `recover_strings` when supported static string recovery is requested;
   - an existing finder record or reconstructed excerpt for a cross-check.
4. **Check bounds.** Bytecode caps executable output at 500; CFG/constant
   blocks cap at 100. Inspect `truncated`; there is no paging parameter.
   Under truncated CFG output, some `unreachableBlocks` are omitted blocks
   and edges can point outside the returned block set. Do not classify these
   as proven dead code.
5. **Evaluate the claim.** Base the requested conclusion on the selected
   evidence. For operand analysis, preserve producer sets, origins, parameter
   locals, JVM widths and merged alternatives. For numeric branch analysis,
   inspect the condition, operands, taken/fallthrough destinations and unknown
   reason. Check status, warnings and truncation. Unknown inputs and conflicting
   merges remain unknown; an unknown result cannot justify branch removal.
   Normal-flow facts do not prove that throwing operations complete.
6. **Return or hand off.** Report the observed condition/call/constant, the
   model's bounds and unresolved assumptions. Any follow-up should carry the
   exact excerpt or observed source anchor and the remaining question.

## Conflicting and missing evidence

Check project/checksum and descriptors before comparing expert output.
Decompiled source is reconstruction; inspect the relevant bytecode when a
semantic conclusion or disputed claim needs it. Agreement between decompilers
does not establish runtime behavior.

Investigate callers or field state only when the requested claim depends on
them. Supplied argument/field values are conditional assumptions: record their
source and scope, or leave them unknown. One indexed site does not establish
all callers or a global invariant; reference scans do not resolve reflection,
virtual dispatch or external dependencies. Tool-specific binding and recovery
details are in the [analysis reference](../../../docs/acrab-manual/ANALYSIS-AND-VERIFICATION.md).

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
