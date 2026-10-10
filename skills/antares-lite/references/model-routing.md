# Intelligence Requirements and Model Routing

These ratings describe task reasoning demands. They are not measured model
scores, probabilities, or a license to delegate. The main agent reads the
host's actual supported inventory before selecting a model. Do not hardcode
model names, invent availability, or infer competence from a name or price.
The portable requirements are in [model-routing.json](model-routing.json).

## Scale

| Level | Required capability |
| --- | --- |
| 1 | Follow a fully specified tool call and report literal fields |
| 2 | Resolve schemas, identity, exact names and bounded pagination |
| 3 | Triage heuristics, preserve coverage and track unresolved evidence |
| 4 | Reason across JVM dataflow, descriptors, contexts and contradictory source |
| 5 | Review complex control flow, cross-method assumptions and semantic risks |

| Role | Minimum | Recommended | Escalated | Typical escalation |
| --- | --- | --- | --- | --- |
| Coordinator | 4 | 4 | 5 | Conflicting claims, complex reconstruction or publication |
| Sirius / indexer | 2 | 2 | 3 | Ambiguous inventory, identity conflicts, reference interpretation |
| Mira / finder | 3 | 3 | 4 | Overload/cursor collisions, combined guard or decoder hypotheses |
| Rigel / analyst | 4 | 4 | 5 | Irreducible loops, merged operands, cross-call guard assumptions |
| Vega / decompiler | 3 | 4 | 5 | Backend disagreement, loop reconstruction, semantic comparison |
| Altair / patcher | 4 | 4 | 5 | Branch deletion, control-flow edits, uncertain publication |

Minimum applies to a routine bounded assignment. Recommended covers the usual
role, and escalated covers the listed difficult cases. A level-3 model can
retrieve a decompilation; reconstructing an obfuscated loop requires level 5.
Split retrieval and semantic review only when delegation is authorized and
independent work can proceed. A model's reasoning-effort setting does not
automatically raise its capability level.

## Selection procedure

1. Preserve explicit single-agent, model, effort, cost and latency constraints.
   Single-agent work follows the same workflow locally. These files never
   override a user-selected model or force a model switch.
2. Read model IDs, capabilities and supported reasoning efforts from the host
   configuration or tool-provided inventory. Use trusted host assessments or
   user-provided capability mappings if available. This package has no built-in
   mapping from volatile model IDs to intelligence levels.
3. Select the role's recommended requirement, raising it to the escalated
   level for its triggers. Require tools/MCP access or readable handoff
   resources and enough context for the scoped evidence. Among adequately
   assessed supported models, prefer lower cost/latency when the host exposes
   those attributes. Never fabricate pricing or benchmark data.
4. If capability assessment is missing, inherit the current model and narrow
   the assignment, or use a supported model the user/host designates for the
   required reasoning. Record that the fit is unassessed. If no adequate
   supported option exists, keep work local or return the specific capability
   gap; do not silently dispatch to an unsupported ID.
5. Keep the user's effort override. Otherwise use a supported effort suitable
   for the task; TOML template efforts are defaults, not mandates. A host that
   does not support model selection can still perform every role locally.

## Dispatch record and escalation

Add this coordinator-owned record to an authorized delegated task:

```json
{
  "role": "antares-lite-bytecode-analyst",
  "requiredIntelligence": 5,
  "modelId": "<supported host model ID>",
  "reasoningEffort": "<supported effort or null>",
  "selectionBasis": "<host/user assessment or inherited-unassessed>",
  "reason": "Merged producers and a disputed loop update"
}
```

Escalate after a concrete unresolved contradiction or failed reasoning check,
not after every unknown value. Supply the disputed evidence and exact scope to
the reviewer. Missing callers, unsupported opcodes or truncated responses need
more evidence or an explicit limitation, not a stronger model's guess. The
coordinator validates returned claims regardless of the selected model.
