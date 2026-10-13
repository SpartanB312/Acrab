# Android Analysis and Conversion

Acrab ports the Android services from **Antares 1.3.5**, pinned to
[`dc8bced35f7c14ccd3a509c261270d24456a2bf4`](https://github.com/SpartanB312/Antares/tree/dc8bced35f7c14ccd3a509c261270d24456a2bf4).
It uses Acrab's MCP registry and verified file-backed project store, not
Antares's database, dashboard or REST project APIs. JVM tools and guarded
method patches remain a separate workflow.

## Inputs and static evidence

Classic DEX versions 035, 037, 038, 039 and 040 use dexlib2 3.0.10. Unsupported
versions, including container DEX 041, fail explicitly. APK/multidex ZIP and
AAB `classes*.dex` entries retain their full source paths. Manifest inspection
supports plain XML, binary AXML and AAB protobuf with base/feature identity.

Import via `create_project(fileName, archiveBase64)`, preserving the original
format extension. Format selection uses `fileName`, not the stored path. Import
stores bytes, not a promise of structural validity; the decoded budget is
128 MiB. Android calls verify current stored source identity.

Use `android_dex_summary`, then bounded class/string/invocation queries or
`android_method_bytecode`. No converter is needed for analysis. Duplicate
class names require `dexFile`; overloaded methods require `descriptor`.
Preserve `inputSha256` and exact selectors. DEX `index` is an instruction
position; `codeOffset`, `codeUnits` and branch offsets use **16-bit code units**,
not JVM byte offsets or ASM positions. Registers are not operand-stack slots.
Sensitive API matches remain static candidates, not evidence of execution.

Manifest questions need no DEX scan or conversion. Preserve parser, path,
modules, warnings, unknowns and explicit versus inferred exported state.
`android_manifest_raw.decodedXml` contains UTF-8 source for plain XML but
Base64 source bytes for AXML/protobuf; it does not promise rendered XML.
Check `encoding`, `byteLength`, `returnedBytes` and `truncated`.

List/string/invocation arrays default to 100, accept 1-500 and have no cursor,
total or truncation field. Method listings default to 500 and accept 1-10,000
with `truncated`; sensitive API findings default to 200 and accept 1-500.
Raw manifests default to 1,000,000 bytes, capped at 4 MiB. Keep parser,
returned and reviewed coverage separate.

## Configuration

Static parsing, ASM checks, checksum repair and header rewriting are built in.
Converters are optional trusted installations, not bundled executables.

| Variable | Default | Purpose |
| --- | --- | --- |
| `ACRAB_ANDROID_TOOL_ROOT` | unset | Trusted converter entry-point directory |
| `ACRAB_ANDROID_PATH_ROOT` | unset | Explicit second trusted directory, not arbitrary PATH |
| `ACRAB_ANDROID_MIN_API` | `21` | Positive D8 minimum API |
| `ACRAB_ANDROID_LIBRARIES` | empty | D8 library files, platform path-separated |
| `ACRAB_ANDROID_CLASSPATH` | empty | D8 classpath files, platform path-separated |
| `ACRAB_ANDROID_D8_VERSION` | unset | Optional expected D8 version |

Inspect `android_dex2jar_status` and `android_dex_tools_catalog`. Allowlisted
logical names do not imply an available/supported conversion. Executable
symlinks must remain inside the trusted root. Control characters are rejected;
Windows batch paths/arguments also reject shell metacharacters. Commands have
a 120-second default timeout and two-process concurrency bound. stdout/stderr
are drained, retaining 64 KiB previews each.

Missing converters report failure and create no output. smali/baksmali use
the pinned 3.0.10 contract; dex2jar uses 2.4.38. D8 requires its configured
prerequisites and validates single/multidex output. Retain actual versions,
arguments and coverage. JAR/WAR/EAR are supported by the Android-side JVM
conversion/verification tools; normal JVM indexing and patches require JAR.

Classic Jasmin assembles project-local `.j` trees for majors 45-52 and rejects
indy/condy. `android_jar2jasmin_convert` is unavailable/unsupported, not a
disassembler. `android_class_version_switch` accepts majors 45-65 but changes
only headers, not features or desugaring. Static checks do not prove runtime
linking, compatibility or behavioral equivalence.

## Outputs and handoff

Conversions, assembly, checksum repair and header changes require authorization
for the requested artifact; read-only analysis does not authorize them. Outputs
are separate from source bytes under `data/android/<projectId>/artifacts/`,
with converter working trees under `logs/`. No automatic report generation,
conversion or archive-wide audit is required.

Inspect actual result fields. Most operations use `completed`, but JAR-to-DEX
uses `outputExists`; exit code 0 alone does not establish valid output.
Baksmali's `outputDirectory` names its ZIP artifact; `sampleFiles` refer to
local smali files. smali/Jasmin assembly takes an existing absolute `inputPath`
inside this project's Android workspace, without symlink traversal. There is
no text-tree upload endpoint; local preparation may remain a prerequisite.

Use `import_android_artifact` for a project-owned derived JAR/DEX/APK/AAB or
multidex ZIP. Its required arguments are source `projectId` and returned
absolute `artifactPath`, with optional `expectedSha256` when known. It rejects
cross-project paths, symlinks and supplied hash mismatch, copies the bytes and
returns a new project record/hash. Verify the new project before Canopus DEX
analysis or Sirius JAR indexing. A baksmali text ZIP is not DEX bytecode.
Keep source/output identity, converter metadata and coverage linked; never
silently reuse the source ID as if conversion changed its contents.

These routes work through the same MCP connection, without legacy Antares
project APIs. Conversion/import is noninterruptible at the HTTP request layer
and may finish after a response timeout; reconcile outputs before retrying.
Android artifact creation does not use JVM patch previews. Checksum repair or
header rewriting can invalidate APK/JAR signatures. Signing, deployment and target execution remain
outside this workflow.

## Source Licenses

Vendored AAPT2 schemas retain AOSP Apache-2.0 headers and pinned-source metadata.
The distribution includes the [license](../../licenses/AOSP-Apache-2.0.txt) and
[notice](../../licenses/AOSP-NOTICE.txt). See
[Canopus's workflow](../../skills/acrab/workflows/android.md) for handoff rules
and the [tool reference](TOOL-REFERENCE.md#android-tools) for arguments.
