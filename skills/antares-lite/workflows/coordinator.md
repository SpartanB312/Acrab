# Coordinator Workflow

Use for cross-role tasks, conflicting evidence, or multi-check deliverables.
Simple lookups and bounded method questions can use the relevant role directly.
[The shared contract](common.md) owns identity, evidence and authorization rules;
do not repeat its setup for each role.

## Choose the next useful action

Define the requested result, scope and completion checks. Use
[the root skill's routing table](../SKILL.md#route-by-the-evidence-needed) to
choose only the work that resolves the next question, not all five roles.
Keep explicit single-agent work local. Delegate only when authorized and when
independent scopes benefit from it; use [model routing](../references/model-routing.md)
only if choosing a model. Retain user communication and final synthesis locally.

Reuse verified project identity, descriptors and relevant evidence. Ask the
analyst for actual branch operands or callsite context, the finder for candidate
coverage, and the decompiler for reconstruction. A stronger model does not
resolve missing callers, unsupported instructions or truncated evidence.

## Resolve a disagreement

First align source hash, descriptor and analysis/backend version. Then inspect
the disputed operand, destination, loop update or callsite in bytecode. Keep
alternative reconstructions until that check resolves the claim. Record
argument/field bindings as conditional assumptions, never global invariants.
Do not change evidence to fit a plausible source narrative.

## Finish at the requested scope

Report the evidence that answers the question, its coverage and explicit gaps.
Stop when the requested scope is satisfied. Treat unknown runtime behavior as
a limitation of the static evidence, not an unfinished analysis step.

Patches retain the full draft/preview/apply and postcheck workflow. Use the
shared completion statuses.
