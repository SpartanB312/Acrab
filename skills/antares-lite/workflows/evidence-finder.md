# Mira Workflow: Triage and Collect Findings

Use with [Mira's skill](../specialists/evidence-finder/SKILL.md) and
[the shared contract](common.md).

## Input and scope

Establish source identity and whether the request is a bounded shortlist,
category inventory or exhaustive collection/review. Finders accept project
scope, not class/method filters. A client-side target filter changes what you
report, not what the server scanned.

## Select the scan

1. Read `finder_catalog` when category vocabulary is not already known for
   this server. Tool scopes and category values are distinct:

   | Tool | Accepted emitted category |
   | --- | --- |
   | `find_number_secrets` | `number` |
   | `find_string_transforms` | `string-transform` |
   | `find_opaque_predicates` | `opaque-guard` |
   | `find_control_flow_flattening` | `flattening` |
   | `find_invokedynamic_dispatch` | `invokedynamic` |

2. For one category, use its finder. For cross-category top-N triage,
   `evidence_summary` gives a ranked aggregate; avoid calling every finder
   first and then scanning them all again. Use `categoryFilter` only with
   published values. Filters intersect a category tool's scope.
3. Set a positive `limit` proportional to the deliverable (default 100,
   maximum 1,000). Check project/checksum, `scanComplete`, `truncated`,
   returned `findings`, `candidateCount` and both summary maps.

## Collect pages without losing coverage

A category finder accepts `initialEvidenceId`; summary does not.

1. Save the first page's total and summaries before applying a cursor.
2. When more results are needed and `truncated` is true, call the same finder
   with the last returned finding ID as `initialEvidenceId`.
3. Keep project, category scope and filter fixed. The cursor is exclusive.
   Sort order is weight descending, then ID. Merge records by source,
   category/subpattern, class/method/descriptor and instruction indexes.
   Current IDs omit descriptors and can collide between same-named overloads;
   ID alone is not a unique evidence key.
4. Cursor-page `candidateCount`, `categorySummary` and `subpatternSummary`
   describe the remaining suffix, not the first page's total. Do not sum them.
5. Finish collection when `truncated` is false, or stop at the declared
   budget and report collected versus uncollected. An empty truncated page
   or repeated cursor/record sequence is a failure to make progress. A collided
   ID resolves the cursor to its first occurrence, so pagination may stall.
6. To exhaust a truncated aggregate, collect its requested categories through
   their individual finders; never pass a cursor to `evidence_summary`.

A cursor that no longer belongs to the result requires identity/filter review
and a fresh first page. Do not switch categories while reusing the cursor.
On collision/stall, restart without a cursor and raise the limit up to 1,000.
Keep distinct descriptors even when IDs match. If this returns all candidates,
collection can finish with composite-key accounting; if still capped, disclose
unavailable coverage rather than retry the ambiguous cursor or claim exhaustion.

## Review and handoff

Keep each finding's ID, class/method, `descriptor`, instruction indexes,
category/subpattern, evidence, weight, confidence, limitations and `nextTools`.
A shortlist should name why each selected finding matters to the user question
and the bytecode check needed, rather than merely repeat its score.

Route branch, string-transform and dispatch questions to Rigel with exact
methods/indexes. A decoder-shaped method is not recovered plaintext.
Invokedynamic bootstrap metadata is not a resolved runtime call target;
ordinary lambdas and string concatenation also use it. Do not execute
transforms or bootstrap methods.

## Completion

For top-N triage, complete after collecting/reviewing that bounded set and
reporting global totals and gaps. For exhaustive review, both collection and
requested investigation must be complete; a finished scan alone is insufficient.
Zero findings means no supported detector emitted a signal in that scope,
not proof of unobfuscated or harmless input.
