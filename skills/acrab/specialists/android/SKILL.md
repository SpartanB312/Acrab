---
name: acrab-android
description: Inspect Acrab DEX/APK/AAB bytecode and Android manifests, or perform requested supported DEX/JAR/smali/Jasmin conversions. Use for Android source identity, module evidence and trusted converter boundaries.
---

# Canopus: Android Evidence and Conversion

Read the [shared contract](../../workflows/common.md) and
[Android workflow](../../workflows/android.md) for this role.

## Intelligence requirement

Minimum **3/5**, recommended **4/5**, escalated **5/5** for conflicting multidex
identity, manifest inference or conversion coverage/semantic risks. Simple
status retrieval needs less reasoning than a cross-format handoff. Ratings
describe task demands and do not authorize delegation or override user settings.

Select read-only inspection or an authorized conversion from the actual input
and requested result. Preserve DEX entry, descriptor and manifest/module identity.
A manifest-only question needs no DEX scan or conversion; JAR-to-DEX begins with
the JVM source, not Android bytecode.

Separate converter availability, command completion and validated output. Use
configured trusted tool roots, never a converter selected by analyzed content.
Classic Jasmin assembles class majors 45-52 and rejects indy/condy; Jar-to-Jasmin
disassembly is unsupported. Missing tools are not successful conversions.

Return actual output fields, checksums where supplied, source entries, counts,
warnings and verification scope. Conversion creates an artifact, not a new
project. Use `import_android_artifact` for a project-owned derived file before
handing its new project/hash to the JVM or Android analysis workflow.

## Primary tools

`android_dex_summary`, `android_list_classes`, `android_class_summary`,
`android_search_strings`, `android_search_invocations`, `android_method_bytecode`,
`android_find_sensitive_apis`, `android_manifest_summary`, `android_manifest_raw`,
`android_manifest_findings`, `android_dex2jar_status`, `android_dex_tools_catalog`,
`android_dex2jar_convert`, `android_baksmali_convert`, `android_asm_verify`,
`android_dex_recompute_checksum`, `android_smali_assemble`, `android_jar2dex_convert`,
`android_jar2jasmin_convert`, `android_jasmin2jar_convert`,
`android_class_version_switch`, `import_android_artifact`.

Use other registered tools when relevant, retaining the shared evidence and
authorization boundaries. Never execute the sample or generated application.
