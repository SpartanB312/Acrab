package net.evrt.acrab.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.evrt.acrab.analysis.evidence.*
import net.evrt.acrab.analysis.index.*
import net.evrt.acrab.analysis.support.*
import net.evrt.acrab.core.*

internal fun registerAnalysisExtensionTools(context: AcrabContext, projects: ProjectRepository, indexes: JvmIndexService) {
    val evidence = BytecodeEvidenceService(projects, indexes)
    val references = ReferenceService(indexes, evidence)
    val comparison = StructureComparison(indexes)
    val recovery = StaticStringRecovery(evidence, indexes)
    jsonTool<ArchiveIdentity>(context, "verify_archive_identity", "Recompute stored archive identity and compare recorded and optional expected SHA-256.",
        schema(listOf("projectId"), mapOf("expectedSha256" to "string"))) {
        projects.verifyIdentity(requiredString(it, "projectId"), optionalString(it, "expectedSha256"))
    }
    jsonTool<ReferenceResult>(context, "method_references", "List paginated exact method callers or callees with instruction indexes; dynamic resolution remains unknown.",
        schema(listOf("projectId", "className", "methodName"), mapOf("methodDescriptor" to "string", "direction" to "string",
            "offset" to "integer", "limit" to "integer"))) {
        references.methodReferences(requiredString(it, "projectId"), requiredString(it, "className"), requiredString(it, "methodName"),
            optionalString(it, "methodDescriptor"), optionalString(it, "direction") ?: "callers",
            optionalInt(it, "offset") ?: 0, optionalInt(it, "limit") ?: 100)
    }
    jsonTool<ReferenceResult>(context, "field_references", "List exact indexed field reads/writes with source method descriptors and instruction indexes.",
        schema(listOf("projectId", "className", "fieldName"), mapOf("fieldName" to "string", "fieldDescriptor" to "string",
            "access" to "string", "offset" to "integer", "limit" to "integer"))) {
        references.fieldReferences(requiredString(it, "projectId"), requiredString(it, "className"), requiredString(it, "fieldName"),
            optionalString(it, "fieldDescriptor"), optionalString(it, "access") ?: "all",
            optionalInt(it, "offset") ?: 0, optionalInt(it, "limit") ?: 100)
    }
    jsonTool<CallContextResult>(context, "method_call_context", "Trace one direct callsite's receiver and arguments under explicit caller assumptions.",
        stringMapProperties(schema(listOf("projectId", "className", "methodName", "instructionIndex"),
            mapOf("methodDescriptor" to "string")), "argumentValues", "fieldValues")) {
        references.callContext(requiredString(it, "projectId"), requiredString(it, "className"), requiredString(it, "methodName"),
            optionalString(it, "methodDescriptor"), requiredInt(it, "instructionIndex"),
            optionalStringMap(it, "argumentValues"), optionalStringMap(it, "fieldValues"))
    }
    jsonTool<StringRecoveryResult>(context, "recover_strings", "Recover supported cyclic-XOR string initializers by bounded static modeling without loading target code.",
        schema(listOf("projectId", "className"))) {
        recovery.recover(requiredString(it, "projectId"), requiredString(it, "className"))
    }
    jsonTool<StructureComparisonResult>(context, "compare_structure", "Compare class/member structure between projects using explicit class and exact member naming maps.",
        stringMapProperties(schema(listOf("sourceProjectId", "outputProjectId"), mapOf("sourceProjectId" to "string",
            "outputProjectId" to "string", "limit" to "integer")), "classNames", "memberNames")) {
        comparison.compare(requiredString(it, "sourceProjectId"), requiredString(it, "outputProjectId"),
            optionalStringMap(it, "classNames"), optionalStringMap(it, "memberNames"), optionalInt(it, "limit") ?: 100)
    }
}

internal fun stringMapProperties(base: JsonObject, vararg names: String): JsonObject = JsonObject(base +
    ("properties" to JsonObject(base.getValue("properties").jsonObject + names.associateWith {
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", buildJsonObject { put("type", "string") })
        }
    })))

private inline fun <reified T> jsonTool(context: AcrabContext, name: String, description: String,
    schema: JsonObject, crossinline handler: (JsonObject) -> T) {
    context.tools.register(ToolDefinition(name, description, schema)) {
        ToolResult.success(analysisJson.encodeToString(handler(it ?: JsonObject(emptyMap()))))
    }
}
