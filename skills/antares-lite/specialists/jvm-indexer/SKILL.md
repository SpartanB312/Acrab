---
name: antares-lite-jvm-indexer
description: Import and identify JVM JAR projects in Antares Lite, establish archive inventory, and locate classes, methods or string constants for a bounded analysis task.
---

# Sirius: JVM Indexer

Turn an archive or project into verified source identity and bounded targets.
Read [the shared contract](../../workflows/common.md) and
[the indexing workflow](../../workflows/jvm-indexer.md).

## Intelligence requirement

Minimum **2/5**, recommended **2/5**, escalation **3/5** for identity conflicts,
ambiguous/multi-release inventory or reference interpretation. Use
[host model routing](../../references/model-routing.md); preserve user choices.

## Preferred tools

`create_project`, `list_projects`, `verify_archive_identity`, `index_archive`,
`archive_summary`, `list_classes`, `search_classes`, `search_strings`,
`class_summary`, `method_references`, `field_references`.

## Decisions that matter

- Reuse a project after fresh `verify_archive_identity`; compare actual stored
  bytes with recorded and expected hashes. Filenames alone do not identify
  input. Import copies bytes into the workspace. Successful import does not
  establish an analyzable JAR; indexing does.
- Resolve the smallest query needed. Class/string queries use case-insensitive
  substring matching, cap results at 1,000 and have no cursor or total match
  count. Repeating a capped query does not retrieve another page.
- Preserve `class_summary.methods[].descriptor`. A string reference's
  method/descriptor/index may be absent for field constants.
- Reference pages retain exact source descriptors/indexes and `nextOffset`.
  Indexed direct references do not resolve reflection, virtual dispatch or
  external callers. A field write being indexed does not prove it executes.
- Entropy and `likely-encrypted` are signals, not decoded values or encryption
  verdicts. Counts describe indexed class entries, not runtime loading,
  dependencies or multi-release selection.

## Deliverable

Return project/SHA-256, index/summary evidence, targets with exact names and
descriptors, query limits/gaps and the next specialist question. Stop inventory
when it answers the request or enables the handoff. Do not turn import into an
unrequested audit, patch or target execution.
