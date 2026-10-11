# Context and Independent Verification

Antares Lite 1.3 adds fresh byte identity, actual operand provenance, direct
reference queries, one-callsite context, bounded static strings and structural
comparison. These are static capabilities; the server does not execute targets.

## Identity and evidence

Record the local SHA-256, import's `sha256` and a fresh
`verify_archive_identity.actualSha256`. Require `matches` and optional
`expectedMatches`; inspect the full fields before reporting a discrepancy.
Index reuse rejects changed stored bytes. Import changed inputs as new projects.
Method evidence includes `inputSha256` and `analysisVersion`; retain these with
the exact class/method/descriptor, raw ASM indexes and complete arguments.

## Explain an actual branch

Select the exact descriptor and locate returned indexes with `method_bytecode`.
Use `method_stack_slice` at each disputed branch, not at the nearest load.
Operands are bottom-to-top among consumed values. A `long`/`double` occupies
two slots but one value. Immediate producers, transitive origins and parameter
locals remain separate; merged alternatives are retained as sets. A singular
producer exists only when unique. Honor provenance budgets/status/truncation.

`cfg_constant_propagation` adds condition expressions, operand facts, taken
target, executable fallthrough target and an unknown reason. Constant results
describe normal-flow arithmetic; unknown inputs and conflicting merges remain
unknown. Truncated CFG reachability cannot establish that omitted blocks are
dead. Reference sites and raw ASM positions are static evidence.

## Supply a bounded context

Page `method_references` for exact callers and `field_references` for all indexed
guard writes. Use `method_call_context` at a direct invocation to obtain numeric
`calleeArgumentValues`. These describe one observed site, not all possible
callers, virtual dispatch, reflection or external dependencies.

Example arguments for a callee analysis:

```json
{
  "projectId": "<projectId>",
  "className": "example.Worker",
  "methodName": "<init>",
  "methodDescriptor": "(JC)V",
  "argumentValues": {"1": "65"},
  "fieldValues": {"example/Flags.enabled:Z": "0"}
}
```

Argument keys are zero-based formal argument ordinals excluding `this`;
`"1"` selects the char, even though the preceding long uses two JVM local slots.
Field keys use internal owners (`example/Guard.enabled:Z`) and string numeric
values. Maps are fixed assumptions, echoed under conditional scope; they do
not prove field writes or an invariant over every call. Record where each
value came from, including the caller descriptor/index or inspected writer.

## Recover strings and compare structure

`recover_strings` models supported cyclic-XOR initializers without loading
target code. Require `status: recovered`, retain ordered keys (including repeated
positions), literals, source indexes and diagnostics. Unsupported/budget-exceeding
patterns return a gap. Preserve exact literal spelling and whitespace rather
than normalizing recovered text.

For a requested comparison, import the second supplied archive separately and
use `compare_structure` with explicit maps. Example mapping objects:

```json
{
  "classNames": {"legacy.Record": "example.Record"},
  "memberNames": {"legacy.Record#a()I": "value", "legacy.Record#b:I": "count"}
}
```

Use verified source member names/descriptors; this illustrates the input format,
not a mapping for any particular archive. Signatures are remapped through class names.
Class/member access, inheritance, descriptors and manifest Main-Class are
compared. Bodies, resource behavior and runtime equivalence are outside this
check. An intentional removal or signature change remains a difference.

## Interpret verification limits

Report archive identity, structural differences, finder coverage and static
verifier results separately when relevant to the task. These checks do not
establish runtime linking or behavioral equivalence.
