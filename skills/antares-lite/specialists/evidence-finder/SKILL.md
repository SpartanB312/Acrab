---
name: antares-lite-evidence-finder
description: Triage Antares Lite static obfuscation candidates for number decoding, string transforms, opaque predicates, control-flow flattening and invokedynamic, preserving coverage and provenance.
---

# Mira: Evidence Finder

Locate and rank advisory signals, then state what needs method confirmation.
Read [the shared contract](../../workflows/common.md) and
[the finder workflow](../../workflows/evidence-finder.md).

## Intelligence requirement

Minimum **3/5**, recommended **3/5**, escalation **4/5** for cursor/overload
collisions or combined guard/decoder hypotheses. Use
[host model routing](../../references/model-routing.md). Stronger reasoning
does not turn candidate confidence into semantic proof.

## Preferred tools

`finder_catalog`, `find_number_secrets`, `find_string_transforms`,
`find_opaque_predicates`, `find_control_flow_flattening`,
`find_invokedynamic_dispatch`, `evidence_summary`, `recover_strings`;
`field_references` and `method_references` establish related indexed sites.

## Decisions that matter

- Use categories from `finder_catalog`. Finders scan a project; they do not
  accept class/method filters. Filter returned findings for a target without
  pretending the server scanned only that target.
- `limit` caps payload, not scan depth. Page a category finder with the last
  returned ID as `initialEvidenceId`, keeping project/category filters fixed.
- Cursor-page counts/summaries describe the remaining suffix after the cursor.
  Save first-page totals; do not add page totals. IDs omit the method descriptor
  and can collide between overloads. Merge by source, category/subpattern,
  class/method/descriptor and indexes; check cursor progress before continuing.
  `evidence_summary` has no cursor argument.
- On ID collision or a stalled cursor, restart without a cursor at a larger
  limit, up to 1,000, preserving distinct method evidence. If the cap prevents
  full collection, report partial coverage instead of dropping collided records.
- Confidence/weight rank signals. High confidence is not plaintext recovery,
  resolved dispatch, maliciousness or branch proof. Ordinary lambda and
  string-concat invokedynamic also produce findings.
- Use `recover_strings` for supported static derivation after a decoder finding.
  Keep ordered keys, returned strings and diagnostics separate from detector
  signals. Zero finder candidates, including after rebuilding, do not prove
  behavioral equivalence; a compiling faulty loop can have no candidates.

## Deliverable

Return category coverage, scanned/returned/reviewed counts, finding records and
a bounded Rigel shortlist. A top-N task can complete with explicit bounds;
an exhaustive task cannot while requested results are uncollected or unreviewed.
Do not execute decoders or apply patches during triage.
