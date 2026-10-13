package net.evrt.acrab.analysis.android

import net.evrt.acrab.analysis.index.ProjectRecord

import java.nio.charset.StandardCharsets
import java.util.Base64

internal class AndroidAnalysisService(
    private val projects: AndroidProjectStore,
    private val reader: DexArchiveReader = DexArchiveReader(),
) {
    suspend fun dexSummary(projectId: String): AndroidDexSummaryResult {
        val project = projects.getProject(projectId)
        val archive = reader.read(project)
        val classes = archive.files.flatMap { it.classes }
        val result = AndroidDexSummaryResult(
            projectId, project.sha256, project.fileName, DexArchiveReader.PARSER_VERSION,
            archive.files.size, classes.size, archive.files.sumOf { it.methods.size }, archive.files.sumOf { it.fields.size }, archive.files.sumOf { it.strings.size },
            classes.groupingBy { it.className.substringBeforeLast('.', "<default>") }.eachCount().entries.sortedByDescending { it.value }.take(50).map { AndroidPackageSummary(it.key, it.value) },
            archive.files.map { AndroidDexFileInfo(it.name, it.classes.size, it.methods.size, it.fields.size, it.strings.size) },
            listOf("DEX parsing is read-only and bounded. Unsupported DEX features and malformed manifests are reported explicitly."),
        )
        return result
    }

    suspend fun listClasses(projectId: String, query: String?, dexFile: String?, limit: Int): List<AndroidClassInfo> {
        val q = query?.trim()?.lowercase().orEmpty()
        return reader.read(projects.getProject(projectId)).files.asSequence()
            .filter { dexFile.isNullOrBlank() || it.name == dexFile }
            .flatMap { it.classes.asSequence() }
            .filter { q.isBlank() || it.className.lowercase().contains(q) || it.type.lowercase().contains(q) }
            .sortedBy { it.className }.take(limit.coerceIn(1, MAX_QUERY)).map(::classInfo).toList()
    }

    suspend fun classSummary(projectId: String, className: String, dexFile: String?): AndroidClassInfo {
        val matches = reader.read(projects.getProject(projectId)).files.asSequence()
            .filter { dexFile.isNullOrBlank() || it.name == dexFile }.flatMap { it.classes.asSequence() }
            .filter { it.className == className }.toList()
        require(matches.isNotEmpty()) { "Android class not found: $className" }
        require(matches.size == 1) { "Ambiguous Android class: $className; supply dexFile." }
        return classInfo(matches.single())
    }

    suspend fun searchStrings(projectId: String, query: String?, dexFile: String?, limit: Int): List<AndroidStringReference> {
        val q = query?.trim()?.lowercase().orEmpty()
        return reader.read(projects.getProject(projectId)).files.asSequence().filter { dexFile.isNullOrBlank() || it.name == dexFile }
            .flatMap { it.stringReferences.asSequence() }.filter { q.isBlank() || it.value.lowercase().contains(q) }.take(limit.coerceIn(1, MAX_QUERY)).toList()
    }

    suspend fun searchInvocations(projectId: String, owner: String?, name: String?, dexFile: String?, limit: Int): List<AndroidInvocationReference> {
        val oq = owner?.trim()?.lowercase().orEmpty(); val nq = name?.trim()?.lowercase().orEmpty()
        return reader.read(projects.getProject(projectId)).files.asSequence().filter { dexFile.isNullOrBlank() || it.name == dexFile }
            .flatMap { it.invocations.asSequence() }.filter { oq.isBlank() || it.targetOwner.lowercase().contains(oq) }
            .filter { nq.isBlank() || it.targetName.lowercase().contains(nq) }.take(limit.coerceIn(1, MAX_QUERY)).toList()
    }

    suspend fun methodBytecode(projectId: String, className: String, methodName: String, descriptor: String?, dexFile: String?, limit: Int): AndroidMethodBytecodeResult {
        val project = projects.getProject(projectId)
        val matches = reader.read(project).files.asSequence().filter { dexFile.isNullOrBlank() || it.name == dexFile }.flatMap { it.methods.asSequence() }
            .filter { it.ownerClass == className && it.name == methodName && (descriptor.isNullOrBlank() || it.descriptor == descriptor) }.toList()
        require(matches.isNotEmpty()) { "Android method not found: $className->$methodName${descriptor.orEmpty()}" }
        require(matches.size == 1) { "Ambiguous Android method: $className->$methodName; supply descriptor and dexFile." }
        val method = matches.single()
        val instructions = method.code?.instructions.orEmpty(); val bounded = instructions.take(limit.coerceIn(1, MAX_INSTRUCTIONS))
        return AndroidMethodBytecodeResult(projectId, project.sha256, method.dexFile, className, method.name, method.descriptor, method.code?.registerCount ?: 0, instructions.size, method.code?.tryBlockCount ?: 0, instructions.size > bounded.size, bounded, listOf("Instruction references are static DEX evidence; unknown opcode payloads are not executed."))
    }

    suspend fun sensitiveApis(projectId: String, limit: Int): AndroidSensitiveFindingsResult {
        val project = projects.getProject(projectId)
        val findings = reader.read(project).files.flatMap { file -> file.invocations.filter(::isSensitive).map { invocation ->
            val category = sensitiveCategory(invocation)
            AndroidSensitiveFinding("$category:${invocation.dexFile}:${invocation.ownerClass}:${invocation.sourceMethod}:${invocation.instructionIndex}", category, if (category in setOf("native", "command", "dynamic-loading")) "high" else "medium", "Sensitive API candidate", "Static invocation matches a sensitive API family; this is a candidate, not proof of behavior.", invocation.dexFile, invocation.ownerClass, invocation.sourceMethod, listOf("${invocation.targetOwner}->${invocation.targetName}${invocation.targetDescriptor}", "instruction=${invocation.instructionIndex}"), listOf("The call may be guarded, unused, or benign.", "No application code was executed."))
        } }.distinctBy { it.id }.sortedBy { it.id }
        val bounded = findings.take(limit.coerceIn(1, MAX_FINDINGS))
        return AndroidSensitiveFindingsResult(projectId, project.sha256, findings.size, findings.size > bounded.size, bounded, listOf("Sensitive API results are static hints with explicit limitations."))
    }

    suspend fun manifestSummary(projectId: String): AndroidManifestSummaryResult {
        val project = projects.getProject(projectId)
        val result = parseManifest(project).summary(projectId, project.sha256, project.fileName)
        return result
    }

    suspend fun manifestRaw(projectId: String, maxBytes: Int): AndroidManifestRawResult {
        val project = projects.getProject(projectId); val (path, bytes) = reader.readManifest(project)
        if (bytes == null) return AndroidManifestRawResult(projectId, project.sha256, project.fileName, path, "missing", "", false, listOf("AndroidManifest.xml is not present in this input."))
        val bounded = bytes.copyOfRange(0, minOf(bytes.size, maxBytes.coerceIn(1, MAX_MANIFEST_BYTES))); val plain = isPlainXml(bytes)
        val protobuf = path != null && AAB_MANIFEST_PATH.matches(path)
        val parser = when {
            plain -> "plain-xml"
            protobuf -> "aab-protobuf"
            else -> "binary-axml"
        }
        val decoded = when {
            plain -> bounded.toString(StandardCharsets.UTF_8)
            else -> Base64.getEncoder().encodeToString(bounded)
        }
        val warning = if (plain) null else "decodedXml contains base64-encoded source bytes, not rendered XML."
        return AndroidManifestRawResult(projectId, project.sha256, project.fileName, path, parser, decoded, bytes.size > bounded.size, listOfNotNull(warning), if (plain) "utf-8" else "base64", bytes.size, bounded.size)
    }

    suspend fun manifestFindings(projectId: String): AndroidManifestFindingsResult {
        val summary = manifestSummary(projectId); val findings = mutableListOf<AndroidManifestFinding>(); val app = summary.application
        if (app?.debuggable == true) findings += hint("debuggable", "medium", "Debuggable application hint", "The manifest explicitly enables debuggable mode.", "application android:debuggable=true")
        if (app?.allowBackup == true) findings += hint("backup", "low", "Backup enabled hint", "Application backup is enabled; review data exposure policy.", "application android:allowBackup=true")
        if (app?.usesCleartextTraffic == true) findings += hint("cleartext", "medium", "Cleartext traffic hint", "The manifest allows cleartext traffic.", "application android:usesCleartextTraffic=true")
        summary.components.filter { it.explicitExported == true }.forEach { component -> findings += hint("exported:${component.type}:${component.name}", "medium", "Exported component hint", "A component is explicitly exported and should be reviewed with its intent filters and permission.", "${component.type} ${component.name}") }
        return AndroidManifestFindingsResult(projectId, summary.inputSha256, summary.parser, findings, listOf("Manifest findings are hints, not exploitability or maliciousness conclusions."))
    }

    private fun classInfo(clazz: DexClassModel) = AndroidClassInfo(clazz.dexFile, clazz.type, clazz.className, clazz.className.substringBeforeLast('.', "<default>"), clazz.accessFlags, clazz.superClass, clazz.interfaces, clazz.sourceFile, clazz.fields.size, clazz.methods.size, clazz.methods.map { method -> AndroidMethodInfo(method.ownerClass, method.name, method.descriptor, method.accessFlags, method.code?.registerCount, method.code?.instructions?.size ?: 0, method.code?.tryBlockCount ?: 0) })
    private fun isSensitive(i: AndroidInvocationReference): Boolean { val target = "${i.targetOwner}.${i.targetName}".lowercase(); return SENSITIVE.any { target.contains(it) } }
    private fun sensitiveCategory(i: AndroidInvocationReference): String { val target = "${i.targetOwner}.${i.targetName}".lowercase(); return when { target.contains("loadlibrary") || target.contains("runtime") -> "native"; target.contains("exec") || target.contains("processbuilder") -> "command"; target.contains("classloader") || target.contains("dexclassloader") -> "dynamic-loading"; target.contains("webview") || target.contains("loadurl") -> "webview"; target.contains("telephony") || target.contains("getdeviceid") -> "telephony"; target.contains("cipher") || target.contains("crypto") -> "crypto"; else -> "sensitive-api" } }
    private fun hint(id: String, severity: String, title: String, description: String, evidence: String) = AndroidManifestFinding(id, "manifest-hint", severity, title, description, listOf(evidence), listOf("Manifest state alone does not establish exploitability or runtime behavior."))
    private fun parseManifest(project: ProjectRecord): ParsedManifest {
        val entries = reader.readManifestEntries(project)
        if (entries.isEmpty()) return ManifestParser.error(null, "missing", "Manifest is missing.")
        val parserResults = entries.map { (path, bytes) ->
            when {
                isPlainXml(bytes) -> runCatching { ManifestParser.parse(path, bytes) }.getOrElse {
                    ManifestParser.error(path, "plain-xml-error", "Manifest XML parse failed: ${it.message}")
                }
                AAB_MANIFEST_PATH.matches(path) -> runCatching { ManifestParser.parseAabProtobuf(path, bytes) }.getOrElse {
                    ManifestParser.error(path, "aab-protobuf-error", "AAB protobuf manifest parse failed: ${it.message}")
                }
                else -> runCatching { ManifestParser.parseBinaryXml(path, bytes) }.getOrElse {
                    ManifestParser.error(path, "binary-axml-error", "Binary AXML parse failed: ${it.message}")
                }
            }
        }
        return if (entries.any { AAB_MANIFEST_PATH.matches(it.first) } || parserResults.size > 1) ManifestParser.mergeModules(parserResults) else parserResults.single()
    }
    companion object {
        private const val MAX_QUERY = 500
        private const val MAX_INSTRUCTIONS = 10_000
        private const val MAX_FINDINGS = 500
        private const val MAX_MANIFEST_BYTES = DexArchiveReader.MAX_MANIFEST_BYTES
        private val SENSITIVE = listOf("webview", "loadurl", "addjavascriptinterface", "runtime", "exec", "processbuilder", "loadlibrary", "java.lang.reflect", "classloader", "dexclassloader", "telephony", "getdeviceid", "android.location", "getlastknownlocation", "javax.crypto", "cipher", "socket", "httpurlconnection", "contentresolver", "clipboard")
    }
}

private val AAB_MANIFEST_PATH = Regex("^(?:[^/]+)/manifest/AndroidManifest.xml$")
