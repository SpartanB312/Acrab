# Intelligence Requirements and Model Routing

The main agent uses this reference when selecting a model for authorized
delegation. Local or inherited-model work needs no additional model lookup.
The [JSON requirements](model-routing.json) support programmatic consumers;
reading both formats is unnecessary.

Ratings express task reasoning demands, not measured model scores. Roles have
different requirements, and a harder assignment may need a higher level within
the same role. Preserve user model, effort, cost and latency constraints.

## Scale and roles

| Level | Required capability |
| --- | --- |
| 1 | Follow a specified tool call and report literal fields |
| 2 | Resolve schemas, identity, exact names and bounded pagination |
| 3 | Triage heuristics, preserve coverage and track unresolved evidence |
| 4 | Reason across bytecode evidence, descriptors, contexts and contradictory evidence |
| 5 | Review complex control flow, cross-method assumptions and semantic risks |

| Role | Minimum | Recommended | Escalated | Escalation conditions |
| --- | --- | --- | --- | --- |
| Coordinator | 4 | 4 | 5 | Complex cross-role reasoning or conflicting evidence |
| Sirius / indexer | 2 | 2 | 3 | Ambiguous inventory, identity conflicts or complex reference interpretation |
| Mira / finder | 3 | 3 | 4 | Interacting candidate signals or complex coverage accounting |
| Rigel / analyst | 4 | 4 | 5 | Complex control flow, cross-method reasoning or conflicting evidence |
| Vega / decompiler | 3 | 4 | 5 | Complex semantic reconstruction or backend disagreements |
| Altair / patcher | 4 | 4 | 5 | Complex control-flow changes or uncertain application state |
| Canopus / Android | 3 | 4 | 5 | Conflicting multidex identity, manifest inference or conversion coverage/semantic risks |

Minimum covers a routine bounded assignment, recommended covers the usual
role, and escalated covers its difficult cases. Retrieval and semantic review
can require different capabilities without becoming mandatory separate tasks.

## Select a model

1. Choose the relevant role and scoped assignment. Use its recommended level,
   or the escalated level when the actual reasoning complexity requires it.
   Respect explicit single-agent work; ratings never authorize delegation.
2. Select from the host's supported models using trusted host assessments or
   user-provided capability mappings. Ensure tools/MCP access and enough context
   for the assignment. Do not infer a numeric capability score from a model name
   or price. This package does not hardcode volatile model-ID mappings.
3. Preserve explicit model and effort choices. Otherwise choose a supported
   model that meets the requirement, considering cost/latency when known. If
   capability fit is unassessed, use the current model or a host/user-designated
   model and state the limitation rather than inventing benchmark data.
4. Include the role, required level and chosen model in the delegated task when
   selecting a model. Reasoning effort is a supported host setting, not a way
   to automatically raise the model's capability rating.

Escalate for a concrete reasoning difficulty or unresolved contradiction, not
every unknown value. Missing callers, unsupported operations or truncated output
need evidence or a stated limitation, not a stronger model's guess. Model
selection never expands the user's requested scope or weakens patch gates.
