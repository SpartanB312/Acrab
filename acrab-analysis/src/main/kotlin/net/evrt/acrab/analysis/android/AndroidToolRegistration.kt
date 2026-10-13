package net.evrt.acrab.analysis.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import net.evrt.acrab.analysis.index.ProjectRepository
import net.evrt.acrab.analysis.support.analysisJson
import net.evrt.acrab.analysis.support.schema
import net.evrt.acrab.core.AcrabContext
import net.evrt.acrab.core.ToolDefinition
import net.evrt.acrab.core.ToolResult

/** Android services ported from Antares 1.3.5, dc8bced35f7c14ccd3a509c261270d24456a2bf4. */
fun registerAndroidTools(context: AcrabContext, repository: ProjectRepository) {
    val projects = AndroidProjectStore(repository)
    val analysis = AndroidAnalysisService(projects)
    val conversion = AndroidToolsService(projects)
    val project = mapOf("projectId" to "string")
    val query = project + mapOf("query" to "string", "dexFile" to "string", "limit" to "integer")

    register(context, "android_dex_summary", "Return bounded DEX/APK/AAB counts, source entries and parser limitations.", project) {
        analysis.dexSummary(it.text("projectId")!!)
    }
    register(context, "android_list_classes", "List bounded Android classes with DEX source identity.", query, bounds = mapOf("limit" to 500)) {
        analysis.listClasses(it.text("projectId")!!, it.text("query"), it.text("dexFile"), it.integer("limit", 100))
    }
    register(context, "android_class_summary", "Return an unambiguous Android class, fields, methods and DEX identity.",
        project + mapOf("className" to "string", "dexFile" to "string"), required = listOf("projectId", "className")) {
        analysis.classSummary(it.text("projectId")!!, it.text("className")!!, it.text("dexFile"))
    }
    register(context, "android_search_strings", "Search bounded static DEX string references without executing target code.", query, bounds = mapOf("limit" to 500)) {
        analysis.searchStrings(it.text("projectId")!!, it.text("query"), it.text("dexFile"), it.integer("limit", 100))
    }
    register(context, "android_search_invocations", "Search bounded static DEX calls, preserving source and target descriptors.",
        project + mapOf("owner" to "string", "name" to "string", "dexFile" to "string", "limit" to "integer"), bounds = mapOf("limit" to 500)) {
        analysis.searchInvocations(it.text("projectId")!!, it.text("owner"), it.text("name"), it.text("dexFile"), it.integer("limit", 100))
    }
    register(context, "android_method_bytecode", "Return exact bounded DEX method instructions; reject ambiguous names or DEX entries.",
        project + mapOf("className" to "string", "methodName" to "string", "descriptor" to "string", "dexFile" to "string", "limit" to "integer"),
        required = listOf("projectId", "className", "methodName"), bounds = mapOf("limit" to 10_000)) {
        analysis.methodBytecode(it.text("projectId")!!, it.text("className")!!, it.text("methodName")!!, it.text("descriptor"), it.text("dexFile"), it.integer("limit", 500))
    }
    register(context, "android_find_sensitive_apis", "Return static sensitive-API candidates, not runtime behavior or malware verdicts.",
        project + mapOf("limit" to "integer"), bounds = mapOf("limit" to 500)) {
        analysis.sensitiveApis(it.text("projectId")!!, it.integer("limit", 200))
    }
    register(context, "android_manifest_summary", "Parse plain XML, binary AXML or AAB protobuf manifests with module identity.", project) {
        analysis.manifestSummary(it.text("projectId")!!)
    }
    register(context, "android_manifest_raw", "Return bounded manifest source: UTF-8 text or Base64 binary bytes, not reconstructed XML.",
        project + mapOf("maxBytes" to "integer"), bounds = mapOf("maxBytes" to DexArchiveReader.MAX_MANIFEST_BYTES)) {
        analysis.manifestRaw(it.text("projectId")!!, it.integer("maxBytes", 1_000_000))
    }
    register(context, "android_manifest_findings", "Return manifest configuration candidates with explicit inference limitations.", project) {
        analysis.manifestFindings(it.text("projectId")!!)
    }
    register(context, "android_dex2jar_status", "Inspect trusted-root converter availability without running target artifacts.", emptyMap(), emptyList()) {
        conversion.dex2jarStatus()
    }
    register(context, "android_dex_tools_catalog", "List Android converter contracts; availability requires separately configured trusted tools.", emptyMap(), emptyList()) {
        conversion.catalog()
    }
    register(context, "android_dex2jar_convert", "Convert DEX/APK/AAB with trusted dex2jar, validate coverage and create a separate artifact.", project) {
        conversion.dex2jar(it.text("projectId")!!)
    }
    register(context, "android_baksmali_convert", "Disassemble DEX entries with trusted baksmali and validate a separate smali archive.", project) {
        conversion.baksmali(it.text("projectId")!!)
    }
    register(context, "android_asm_verify", "Perform offline ASM checks of JVM archive classes without executing them.", project) {
        conversion.asmVerify(it.text("projectId")!!)
    }
    register(context, "android_dex_recompute_checksum", "Write a separate artifact with repaired DEX signature/checksum metadata, not instruction changes.", project) {
        conversion.recomputeChecksum(it.text("projectId")!!)
    }
    register(context, "android_smali_assemble", "Assemble a project-local smali tree with a trusted tool; validate the derived DEX.",
        project + mapOf("inputPath" to "string"), listOf("projectId", "inputPath")) {
        conversion.smaliAssemble(it.text("projectId")!!, it.text("inputPath"))
    }
    register(context, "android_jar2dex_convert", "Convert JVM archives with trusted D8/dx; validate DEX or multidex ZIP output.", project) {
        conversion.jar2dex(it.text("projectId")!!)
    }
    register(context, "android_jar2jasmin_convert", "Report Jar-to-Jasmin disassembly as unavailable/unsupported; create no artifact.", project) {
        conversion.jar2jasmin(it.text("projectId")!!)
    }
    register(context, "android_jasmin2jar_convert", "Assemble project-local classic Jasmin sources (major 45-52), validate classes and preserve resources.",
        project + mapOf("inputPath" to "string"), listOf("projectId", "inputPath")) {
        conversion.jasmin2jar(it.text("projectId")!!, it.text("inputPath"))
    }
    register(context, "android_class_version_switch", "Write a separate JVM artifact changing only major-version headers; no desugaring or runtime guarantee.",
        project + mapOf("targetMajorVersion" to "integer"), listOf("projectId", "targetMajorVersion"),
        bounds = mapOf("targetMajorVersion" to 65), minimums = mapOf("targetMajorVersion" to 45)) {
        conversion.classVersionSwitch(it.text("projectId")!!, it.integer("targetMajorVersion", 0))
    }
    register(context, "import_android_artifact", "Import a project-owned derived JAR/DEX/APK/AAB/multidex ZIP as a new project, optionally checking its SHA-256; never read arbitrary server paths.",
        project + mapOf("artifactPath" to "string", "expectedSha256" to "string"), listOf("projectId", "artifactPath")) {
        projects.importArtifact(it.text("projectId")!!, it.text("artifactPath")!!, it.text("expectedSha256"))
    }
}

private inline fun <reified T> register(
    context: AcrabContext,
    name: String,
    description: String,
    properties: Map<String, String>,
    required: List<String> = listOf("projectId"),
    bounds: Map<String, Int> = emptyMap(),
    minimums: Map<String, Int> = emptyMap(),
    crossinline action: suspend (JsonObject) -> T,
) {
    val basic = schema(required, properties)
    val constrained = JsonObject(basic + ("properties" to buildJsonObject {
        properties.forEach { (key, type) ->
            put(key, buildJsonObject {
                put("type", type)
                if (key in required && type == "string") put("minLength", 1)
                bounds[key]?.let { put("minimum", minimums[key] ?: 1); put("maximum", it) }
            })
        }
    }))
    context.tools.register(ToolDefinition(name, description, constrained)) { supplied ->
        val arguments = supplied ?: JsonObject(emptyMap())
        arguments.forEach { (key, value) ->
            if (value == JsonNull && key !in required) return@forEach
            val primitive = value as? JsonPrimitive
            when (properties.getValue(key)) {
                "string" -> require(primitive?.isString == true && (key !in required || primitive.content.isNotBlank())) { "$key must be a string${if (key in required) " that is not blank" else ""}." }
                "integer" -> {
                    val number = primitive?.takeUnless { it.isString }?.intOrNull
                    require(number != null && (key !in bounds || number in (minimums[key] ?: 1)..bounds.getValue(key))) { "$key must be an integer within the advertised bounds." }
                }
            }
        }
        ToolResult.success(runBlocking { analysisJson.encodeToString(action(arguments)) })
    }
}

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content
private fun JsonObject.integer(name: String, default: Int): Int = (get(name) as? JsonPrimitive)?.intOrNull ?: default
