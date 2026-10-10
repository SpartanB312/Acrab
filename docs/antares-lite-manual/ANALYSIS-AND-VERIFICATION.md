# Context and Independent Verification

Antares Lite 1.3.0 adds fresh byte identity, actual operand provenance, direct
reference queries, one-callsite context, bounded static strings and structural
comparison. The server remains static. External fixture tests are separate
repository tools, not target-execution endpoints.

## Identity and evidence

Record the local SHA-256, import's `sha256` and a fresh
`verify_archive_identity.actualSha256`. Require `matches` and optional
`expectedMatches`; inspect the full fields before reporting a discrepancy.
Index reuse rejects changed stored bytes. Import changed inputs as new projects.
Method evidence includes `inputSha256` and `analysisVersion`; retain these with
the exact class/method/descriptor, raw ASM indexes and complete arguments.

The stdlib Python client supports UTF-8 argument files, complete response files
and `--evidence-dir`. Each run exports original requests/responses and a manifest
under a distinct directory. `--verify-evidence <export-directory>` checks file
hashes, path containment, response IDs and recorded source metadata. Exports
preserve full requests, including archive bytes on import. These integrity
checks do not authenticate a writer who can recompute the manifest or prove
that a claim about the program is true.

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

The public fixture's `e.e(J)V` branch at index 73 consumes the load at index 68,
local 4, and jumps to label index 86. It tests the trim counter. A nearby
`GETSTATIC` is not that branch's input. The decrement and removal paths must be
checked separately to reconstruct the loop correctly.

## Supply a bounded context

Page `method_references` for exact callers and `field_references` for all indexed
guard writes. Use `method_call_context` at a direct invocation to obtain numeric
`calleeArgumentValues`. These describe one observed site, not all possible
callers, virtual dispatch, reflection or external dependencies.

Example arguments for a callee analysis:

```json
{
  "projectId": "<projectId>",
  "className": "f",
  "methodName": "<init>",
  "methodDescriptor": "(JC)V",
  "argumentValues": {"1": "2429"},
  "fieldValues": {"e.h:Z": "0"}
}
```

Argument keys are zero-based formal argument ordinals excluding `this`;
`"1"` selects the char, even though the preceding long uses two JVM local slots.
Field keys use internal owners (`example/Guard.enabled:Z`) and string numeric
values. Maps are fixed assumptions, echoed under conditional scope; they do
not prove field writes or an invariant over every call. Record where each
value came from, including the caller descriptor/index or inspected writer.

For `case/snake.jar`, the observed constructor char is 2429. This context
supports the inspected false guard write; it does not justify declaring the
field unassigned or constant under arbitrary constructor arguments.

## Recover strings and compare structure

`recover_strings` models supported cyclic-XOR initializers without loading
target code. Require `status: recovered`, retain ordered keys (including repeated
positions), literals, source indexes and diagnostics. Unsupported/budget-exceeding
patterns return a gap. The fixture recovers `Snake`, `COLISION! \n` and `Yummy!`,
preserving the original misspelling and newline.

Import a rebuilt archive separately and use `compare_structure` with explicit
maps. Example mapping objects:

```json
{
  "classNames": {"c": "snake.Snake", "e": "snake.GameThread"},
  "memberNames": {"e#e(J)V": "trimBodySegments", "e#c:I": "score"}
}
```

Use verified source member names/descriptors; this illustrates the input format,
not a complete fixture mapping. Signatures are remapped through class names.
Class/member access, inheritance, descriptors and manifest Main-Class are
compared. Bodies, resource behavior and runtime equivalence are outside this
check. An intentional removal or signature change remains a difference.

## Validate artifacts independently

Run these from a repository checkout with JDK 21 and Python 3.11 or newer:

```sh
python -m unittest discover -s scripts -p test_antares_client.py -v
python scripts/verify_case.py --report build/reports/case-differential.json
python scripts/verify_case.py --endpoint http://127.0.0.1:8080/mcp --report build/reports/case-with-finders.json
python scripts/package_case.py
python scripts/verify_distribution.py --version 1.3.0 --report build/reports/distribution.json
```

Build `installDist`, `distZip` and `distTar` before distribution verification.
The verifier cleanly extracts both archives, compares resources, starts their
actual launchers, checks MCP lifecycle/catalog/schema/version, then builds the
source ZIP through its packaged script and compares seven class files against
a clean source-tree build using the same JDK. These helper checks need a
checkout; only the reusable HTTP client is included in server distributions.

The external `CaseProbe` uses the exact known fixture and reconstructed archive
in a separate headless JVM. It avoids game constructors/thread/window startup
and compares 36 trim and 16 direction cases. Inputs include the recorded positive
key `41896598891982` and explicitly false guards. It compares retained
coordinates, not just length. A deliberate no-op counter variant still compiles
and has zero finder candidates, but must fail the probe. The scripts bound
subprocess duration and verify fixture bytes before running them.

Report each check separately: identity, compilation, structural differences,
finder scan/payload coverage, static verifier and external behavioral cases.
The probe establishes only those bounded inputs, not random food behavior,
arbitrary keys/guard states, environment changes or whole-program equivalence.
