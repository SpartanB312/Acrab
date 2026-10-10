# Coordinator Workflow: Decide, Corroborate, Finish

Use with [the root skill](../SKILL.md) and [the shared contract](common.md).
Keep the user goal, evidence ledger and final synthesis with the main agent.

## Start with a completion condition

Record the deliverable, exact scope, requested coverage, permitted effects and
checks that would establish completion. For a branch explanation, answer its
actual condition and destinations. For a readable source deliverable, separate
reconstruction, compilation, structure and bounded behavioral verification.
For patching, preserve the requested stage and existing apply authorization.

Do the immediate blocking step locally. A simple lookup or one-method question
does not need all roles. When the user requires one agent, perform the relevant
roles yourself. If delegation is explicitly permitted, give independent tasks
disjoint scopes and stopping conditions. Use [model routing](../references/model-routing.md)
to choose from the host inventory; a rating never authorizes delegation.

## Establish a trustworthy ledger

Initialize the connection, send `notifications/initialized`, discover schemas
and retain the session ID. Use the bundled file client for UTF-8 arguments and
complete response exports when a connector is unavailable. Record source bytes'
hash, imported project hash and `verify_archive_identity` actual hash. Compare
the actual response fields; do not claim a mismatch from memory or abbreviated
terminal output. On disagreement, stop combining results and verify again.

Save evidence by exact project/hash, class, method descriptor, raw ASM indexes,
tool arguments, analysis/backend version and response location. Record
assumptions and scan/payload/review coverage separately. Evidence exports can
detect altered files; their checksums do not authenticate the author or prove
the analysis correct. Never embed Base64 archive payloads in the final report.

## Select the next check

| Question | Role and evidence | Decision rule |
| --- | --- | --- |
| Which bytes/classes/methods? | Sirius: fresh identity, inventory, exact descriptors | Names alone cannot establish identity |
| What does this branch consume? | Rigel: stack provenance, CFG, branch operands and targets | Retain merged producer sets and unknowns |
| Where does a guard/call argument come from? | Rigel/Sirius: references, one call context, explicit assumptions | One observed call is not all callers |
| Which transformations matter? | Mira: finder categories and complete requested coverage | A signal needs a concrete method check |
| Can strings be recovered? | Rigel/Mira: bounded `recover_strings` derivation | Unsupported results remain unknown; do not run decoders |
| Is reconstructed code faithful? | Vega/Rigel: source, exact bytecode, mapped structure | Compilation and finder-zero do not prove behavior |
| Is a change ready to publish? | Altair: valid preview, exact request, postchecks | Static verification has explicit semantic limits |

For a static field conclusion, enumerate indexed writes and inspect their
conditions with `field_references`, caller evidence and argument facts. Keep
reflection, dependencies, unseen callers and alternate initialization contexts
unresolved. Feed `calleeArgumentValues` into a selected callee only as a recorded
assumption. Escalating model capability cannot supply missing program evidence.

## Reconcile material disagreements

First align hash, descriptor and analysis/backend version. Then locate the
disputed producer, branch destination, loop update or callsite in bytecode.
Retain both reconstructions until this check resolves the property. Never
change the underlying evidence to fit a plausible source narrative.

For a reconstructed loop, explicitly check counter initialization, test,
update, side-effect path and retained elements. A nontrivial behavior claim
needs an independent check suited to the task. A separately authorized external
probe may test bounded fixture inputs, but is never an MCP target-execution
tool or a whole-program proof. Record exact input key/guard/environment values
and require a relevant negative control when claiming the probe detects a bug.

## Finish with reviewable artifacts

Record source/output hashes and report each check independently: identity,
analysis, compilation, structural comparison, detector coverage, static verifier
and any external behavioral cases. For source/distribution packages, verify clean
extraction, the packaged build script or launcher, and packaged resources.
Requested commits belong after validated milestones; do not push implicitly.
Use complete/partial/blocked as defined by the shared contract, preserving all
requested scope and explicit unknowns in the final answer.
