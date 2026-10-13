# Canopus Workflow: Android Evidence and Conversion

Use with the [Android skill](../specialists/android/SKILL.md) and
[shared contract](common.md). Select only the steps needed for the request.

## Input and evidence

Import DEX/APK/AAB, multidex ZIP or manifest XML with `create_project`, or reuse
an existing verified project. Retain `projectId` and `sha256`; Android evidence
calls recompute stored source identity and reject changed bytes.

For bytecode inventory, use `android_dex_summary`. Locate classes with
`android_list_classes` and resolve exact descriptors with `android_class_summary`.
Duplicate class names across DEX entries require `dexFile`; method evidence
requires `descriptor` when overloads are ambiguous. Copy returned selectors,
not guessed APK entry names. `android_method_bytecode` uses `descriptor`, unlike
the JVM evidence tools' `methodDescriptor` request parameter.

Preserve instruction `index`, `codeOffset` and `codeUnits`: offsets/widths are
16-bit DEX code units, not JVM byte offsets or raw ASM positions. Register numbers
are not operand-stack slots. Searches return bounded arrays with no cursor/total;
a full-sized result may be capped. Sensitive API matches remain static candidates,
not demonstrated calls or a malware verdict.

For manifest questions, use `android_manifest_summary` and raw/findings only
when needed. Retain `manifestPath`, parser, modules, warnings and unknown values.
Separate `explicitExported` from `effectiveExported` and its inference limits.
`android_manifest_raw.decodedXml` contains UTF-8 source for plain XML, but Base64
source bytes for AXML/protobuf, as indicated by `encoding`. It is not rendered XML.
No DEX scan, conversion or JVM optimization is required for a manifest answer.

## Authorized conversion

Confirm the requested source/output and existing authorization before creating
an artifact. Reuse `android_dex2jar_status` or `android_dex_tools_catalog` when
current. A catalog entry does not establish executable availability or success.

Tools resolve only from `ACRAB_ANDROID_TOOL_ROOT` or the explicitly configured
`ACRAB_ANDROID_PATH_ROOT`, never arbitrary PATH. Allowlisted logical names are
d2j-dex2jar, dex2jar, baksmali, smali, d8, dx, jasmin and r8; not every name has
an exposed successful workflow. Do not download or run archive-selected tools.
Preserve D8 min API, libraries/classpath and version settings; see the
[Android guide](../../../docs/acrab-manual/ANDROID.md#configuration).

| Operation | Result to inspect |
| --- | --- |
| `android_dex2jar_convert` | `completed`, `outputJarPath`, source DEX coverage, commands, class/member counts |
| `android_baksmali_convert` | `completed`, `outputDirectory` (a ZIP artifact), source entries and smali counts |
| `android_smali_assemble` | `completed`, `outputDexPath`, parsed counts and command |
| `android_jar2dex_convert` | `outputExists`, `outputDexPath`, `outputFormat`, `dexFiles`, counts and command; no top-level `completed` |
| `android_jasmin2jar_convert` | `completed`, `outputExists`, `outputJarPath`, class count and command |
| `android_dex_recompute_checksum` | `completed`, `outputArtifactPath`, `outputs[].sha256` and DEX count |
| `android_class_version_switch` | `completed`, `outputJarPath`, major version and changed count |
| `android_asm_verify` | `issueCount`, issues, truncation and warnings; no generic `verified` flag |

Review errors, timeouts, actual output existence and validation, not merely exit
code 0. Preserve partial/missing entry coverage. Do not invent checksums or
`outputProjectId`. Major-version rewriting changes headers, not language features
or desugaring, and does not prove runtime compatibility.

Smali/Jasmin assembly accepts an existing absolute `inputPath` inside
`data/android/<projectId>/`. There is no MCP text-tree upload endpoint. Use a
permitted local tree or report the missing input preparation. Classic Jasmin
supports majors 45-52, rejects indy/condy and preserves resources. The
`android_jar2jasmin_convert` endpoint never produces disassembly.

## Derived project handoff

For a successful project-owned JAR/DEX/APK/AAB/multidex ZIP artifact, call
`import_android_artifact` with the source `projectId`, returned artifact path
and `expectedSha256` when known. It returns a new `ProjectRecord`, copies the
derived bytes and rejects cross-project paths, symlinks and supplied hash mismatch.
Retain source and output identity separately; verify the new project before reuse.

For a derived JAR, continue with Sirius and the existing JVM workflow. For
derived DEX/multidex ZIP, use Canopus. A baksmali ZIP is a text artifact, not
DEX bytecode; use its actual local smali tree for assembly rather than treating
it as an analysis-ready DEX project. Do not reuse the source project ID as if
conversion changed its contents. Acrab needs no legacy Antares REST/import API.

Complete when the requested bounded evidence or validated supported artifact
is delivered. Converting or statically verifying an artifact never authorizes
executing it, and does not prove behavioral equivalence or APK signing validity.
