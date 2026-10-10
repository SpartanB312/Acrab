# Sirius Workflow: Identify and Locate

Use with [Sirius's skill](../specialists/jvm-indexer/SKILL.md) and
[the shared contract](common.md).

## Input and scope

Accept either archive bytes available to the MCP client or an existing
`projectId`. Record the question and query/result budget. Capability discovery
does not require import. A server-local pathname is not an import parameter;
`create_project` requires `fileName` and `archiveBase64`.

## Procedure and checks

1. **Identify.** For an existing project, use supplied trustworthy record
   evidence or `list_projects`; match project ID and SHA-256, not filename.
   For new input, import once, preserve returned `projectId`, `sha256`,
   `fileName` and `archivePath`. Do not place Base64 payloads in reports.
2. **Index.** Call `index_archive(projectId)` for a new project. Import can
   accept bytes that later fail indexing; do not call that analysis-ready.
   Existing indexed projects can reuse their summary. `archive_summary` and
   query tools also ensure an index, so repeated indexing is unnecessary.
3. **Check identity.** Use `verify_archive_identity` with the expected hash
   when available. Require `matches` and, when supplied, `expectedMatches`.
   Record actual and recorded hashes from the response, not memory. Obtain
   `archive_summary` and compare `archiveSha256`
   to the project record and any caller-supplied expected checksum. Record
   class/package/method/field/Kotlin counts and manifest entry point when
   present. These are inventory observations, not runtime entry-point proof.
4. **Locate.** Use `search_classes` with required `query`, or `list_classes`
   with optional `query`. Use `search_strings` for constant text. Default
   limits are 100; explicit positive limits are capped at 1,000.
   Matching is a case-insensitive substring, not regex, glob or whole-program
   full-text search.
5. **Resolve.** Call `class_summary` for selected classes; preserve `name`,
   `internalName`, access flags, methods and JVM descriptors. Use a string
   reference's exact owner/method/descriptor/index where present; field
   constants may have no method anchor.
6. **Hand off.** Send the same identity/summary and the smallest target set
   needed by Rigel, Mira, Vega or Altair. State the remaining question and
   evidence pointers, not merely "analyze this class".

When the question concerns callers or field use, page `method_references` or
`field_references` with the exact descriptor and fixed direction/access filters.
Follow `nextOffset` until the requested coverage is reached. Keep total versus
returned counts and raw source instruction indexes. These are direct indexed
sites, not a resolved runtime call graph or proof a writer executes.

## Coverage and recovery

Query arrays have no total count, cursor or `truncated` flag. A returned count
equal to the effective limit may be capped; report that possibility. Raising
the limit or using a narrower substring can help; repeating the same query is
not pagination. An empty query result supports only "no indexed match for this
query", not absence of behavior. There is no resource-content search tool.

The index scans class entries and does not model dependency resolution or
runtime multi-release class selection. Duplicate/versioned class entries can
make name-only inventory misleading; do not claim a runtime class was selected.

If indexing fails, report the import record separately from index failure and
its diagnostic. Do not silently delete a project or retry with different bytes.
Changed input becomes a new import with a new checksum.

## Completion

Complete when identity/summary and requested bounded targets are verified.
For a full inventory request that exceeds query limits, return partial with
the exposed counts and missing coverage. Preserve entropy/classification as
candidate metadata rather than decoded strings. Return the shared handoff
with the query and effective limit.
