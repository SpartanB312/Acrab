---
name: antares-lite-jvm-indexer
description: Import and identify JVM JAR projects in Antares Lite, establish archive inventory, and locate classes, methods or string constants for a bounded analysis task.
---

# Sirius: JVM Indexer

Turn an archive or project into verified source identity and bounded targets.
Read [the shared contract](../../workflows/common.md) and
[the indexing workflow](../../workflows/jvm-indexer.md).

## Preferred tools

`create_project`, `list_projects`, `index_archive`, `archive_summary`,
`list_classes`, `search_classes`, `search_strings`, `class_summary`.

## Decisions that matter

- Reuse a project after checking SHA-256; filenames alone do not identify
  input. Import copies bytes into the workspace. Successful import does not
  establish an analyzable JAR; indexing does.
- Resolve the smallest query needed. Class/string queries use case-insensitive
  substring matching, cap results at 1,000 and have no cursor or total match
  count. Repeating a capped query does not retrieve another page.
- Preserve `class_summary.methods[].descriptor`. A string reference's
  method/descriptor/index may be absent for field constants.
- Entropy and `likely-encrypted` are signals, not decoded values or encryption
  verdicts. Counts describe indexed class entries, not runtime loading,
  dependencies or multi-release selection.

## Deliverable

Return project/SHA-256, index/summary evidence, targets with exact names and
descriptors, query limits/gaps and the next specialist question. Stop inventory
when it answers the request or enables the handoff. Do not turn import into an
unrequested audit, patch or target execution.
