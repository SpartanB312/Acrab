package net.evrt.antarelite.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import net.evrt.antarelite.analysis.index.ClassInfo
import net.evrt.antarelite.analysis.index.JarSummary
import net.evrt.antarelite.analysis.index.JvmIndexService
import net.evrt.antarelite.analysis.index.ProjectRepository
import net.evrt.antarelite.analysis.index.ProjectRecord
import net.evrt.antarelite.analysis.index.StringReference
import net.evrt.antarelite.analysis.evidence.BytecodeEvidenceService
import net.evrt.antarelite.analysis.evidence.ConstantPropagationResult
import net.evrt.antarelite.analysis.evidence.MethodBytecodeResult
import net.evrt.antarelite.analysis.evidence.MethodCfgResult
import net.evrt.antarelite.analysis.evidence.MethodStackSliceResult
import net.evrt.antarelite.analysis.obfuscation.EvidenceFinderService
import net.evrt.antarelite.analysis.obfuscation.FinderCatalogResult
import net.evrt.antarelite.analysis.obfuscation.FinderResult
import net.evrt.antarelite.analysis.decompiler.DecompilerService
import net.evrt.antarelite.analysis.decompiler.DecompilerBackendInfo
import net.evrt.antarelite.analysis.decompiler.DecompileResult
import net.evrt.antarelite.analysis.patch.BytecodePatchApplyRequest
import net.evrt.antarelite.analysis.patch.BytecodePatchApplyResult
import net.evrt.antarelite.analysis.patch.BytecodePatchCatalogResult
import net.evrt.antarelite.analysis.patch.BytecodePatchDraftResult
import net.evrt.antarelite.analysis.patch.BytecodePatchPreviewResult
import net.evrt.antarelite.analysis.patch.BytecodePatchRequest
import net.evrt.antarelite.analysis.patch.BytecodePatchService
import net.evrt.antarelite.analysis.support.analysisJson
import net.evrt.antarelite.analysis.support.optionalInt
import net.evrt.antarelite.analysis.support.optionalString
import net.evrt.antarelite.analysis.support.optionalStringList
import net.evrt.antarelite.analysis.support.requiredInt
import net.evrt.antarelite.analysis.support.requiredString
import net.evrt.antarelite.analysis.support.schema
import net.evrt.antarelite.core.LiteConfig
import net.evrt.antarelite.core.LiteContext
import net.evrt.antarelite.core.ToolDefinition
import net.evrt.antarelite.core.ToolEnvelope
import net.evrt.antarelite.core.ToolInputException
import net.evrt.antarelite.core.ToolResult

fun registerLiteTools(context: LiteContext) {
    context.checkOpen()
    val projects = ProjectRepository(context.config)
    context.registerResource(projects)
    val indexes = JvmIndexService(projects)
    context.registerResource(indexes)
    val decompiler = DecompilerService(projects, indexes)
    val patcher = BytecodePatchService(projects, indexes)
    context.registerResource(patcher)

    registerProjectTools(context, projects)
    registerReconTools(context, indexes)
    registerDecompilerTools(context, decompiler)
    registerPatchTools(context, patcher)
    registerEvidenceTools(context, projects, indexes)
    registerFinderTools(context, indexes)
    registerBaseTools(context)
}

private fun registerBaseTools(context: LiteContext) {
    context.tools.register(
        ToolDefinition(
            name = "health",
            description = "Return Lite server status and configured version.",
            inputSchema = schema(),
        ),
    ) {
        ToolResult.success(
            """
            status: ready
            server: ${context.config.serverName}
            version: ${context.config.serverVersion}
            registeredEndpoints: ${context.tools.definitions().size}
            analysisTools: ${context.tools.analysisDefinitions().size}
            platformEndpoints: ${context.tools.definitions().size - context.tools.analysisDefinitions().size}
            """.trimIndent(),
        )
    }

    context.tools.register(
        ToolDefinition(
            "list_tools",
            "List registered Lite tools with names, descriptions, argument envelopes, and input schemas.",
            schema(),
        ),
    ) {
        ToolResult.success(
            analysisJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(serializer<ToolDefinition>()),
                context.tools.analysisDefinitions(),
            ),
        )
    }
}

private fun registerProjectTools(context: LiteContext, projects: ProjectRepository) {
    context.tools.register(
        ToolDefinition(
            "create_project",
            "Import a JAR archive into the Lite workspace and create a project.",
            schema(
                required = listOf("fileName", "archiveBase64"),
                properties = mapOf(
                    "fileName" to "string",
                    "archiveBase64" to "string",
                ),
            ),
        ),
    ) { arguments ->
        val fileName = requiredString(arguments, "fileName")
        val bytes = try {
            java.util.Base64.getDecoder().decode(requiredString(arguments, "archiveBase64"))
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("archiveBase64 is not valid Base64.")
        }
        ToolResult.success(
            analysisJson.encodeToString(serializer<ProjectRecord>(), projects.createFromBytes(fileName, bytes)),
        )
    }

    context.tools.register(
        ToolDefinition("list_projects", "List imported archive projects.", schema()),
    ) {
        ToolResult.success(
            analysisJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(serializer<ProjectRecord>()),
                projects.list(),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "index_archive",
            "Index a JAR archive and return bounded structural counts.",
            schema(required = listOf("projectId")),
        ),
    ) { arguments ->
        val index = context.indexes().ensureIndex(requiredString(arguments, "projectId"))
        ToolResult.success(
            analysisJson.encodeToString(
                buildJsonObject {
                    put("projectId", index.summary.projectId)
                    put("archiveSha256", index.summary.archiveSha256)
                    put("classCount", index.summary.classCount)
                    put("methodCount", index.summary.methodCount)
                    put("fieldCount", index.summary.fieldCount)
                    put("stringCount", index.strings.size)
                    put("invocationCount", index.invocations.size)
                },
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "archive_summary",
            "Return bounded structural summary for an indexed JAR archive.",
            schema(required = listOf("projectId")),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<JarSummary>(),
                context.indexes().archiveSummary(requiredString(arguments, "projectId")),
            ),
        )
    }
}

private fun registerReconTools(context: LiteContext, indexes: JvmIndexService) {
    context.tools.register(
        ToolDefinition(
            "list_classes",
            "List indexed JVM classes with an optional bounded name filter.",
            schema(
                required = listOf("projectId"),
                properties = mapOf("query" to "string", "limit" to "integer"),
            ),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(serializer<ClassInfo>()),
                indexes.listClasses(
                    projectId = requiredString(arguments, "projectId"),
                    query = optionalString(arguments, "query"),
                    limit = optionalInt(arguments, "limit") ?: 100,
                ),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "search_classes",
            "Search indexed JVM classes by bounded substring match.",
            schema(
                required = listOf("projectId", "query"),
                properties = mapOf("limit" to "integer"),
            ),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(serializer<ClassInfo>()),
                indexes.listClasses(
                    projectId = requiredString(arguments, "projectId"),
                    query = requiredString(arguments, "query"),
                    limit = optionalInt(arguments, "limit") ?: 100,
                ),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "search_strings",
            "Search bounded string constants in an indexed archive.",
            schema(
                required = listOf("projectId"),
                properties = mapOf("query" to "string", "limit" to "integer"),
            ),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(serializer<StringReference>()),
                indexes.searchStrings(
                    projectId = requiredString(arguments, "projectId"),
                    query = optionalString(arguments, "query"),
                    limit = optionalInt(arguments, "limit") ?: 100,
                ),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "class_summary",
            "Return fields, methods, inheritance, and annotations for one indexed class.",
            schema(required = listOf("projectId", "className")),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<ClassInfo>(),
                indexes.classSummary(
                    requiredString(arguments, "projectId"),
                    requiredString(arguments, "className"),
                ),
            ),
        )
    }
}

private fun registerDecompilerTools(context: LiteContext, service: DecompilerService) {
    context.tools.register(
        ToolDefinition(
            "list_decompiler_backends",
            "List Lite decompiler backend capability and reconstructed-output warnings.",
            schema(),
        ),
    ) {
        ToolResult.success(
            analysisJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(serializer<DecompilerBackendInfo>()),
                service.backendIds().map { service.backendInfo(it) },
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "decompile_class",
            "Reconstruct one class with the selected backend. Verify important claims against bytecode evidence.",
            schema(
                required = listOf("projectId", "className"),
                properties = mapOf("backend" to "string"),
            ),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<DecompileResult>(),
                service.decompileClass(
                    projectId = requiredString(arguments, "projectId"),
                    className = requiredString(arguments, "className"),
                    backend = optionalString(arguments, "backend") ?: "cfr",
                ),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "decompile_method",
            "Extract one reconstructed method from the selected backend output; bytecode evidence remains authoritative.",
            schema(
                required = listOf("projectId", "className", "methodName"),
                properties = mapOf(
                    "methodDescriptor" to "string",
                    "backend" to "string",
                ),
            ),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<DecompileResult>(),
                service.decompileMethod(
                    projectId = requiredString(arguments, "projectId"),
                    className = requiredString(arguments, "className"),
                    methodName = requiredString(arguments, "methodName"),
                    methodDescriptor = optionalString(arguments, "methodDescriptor"),
                    backend = optionalString(arguments, "backend") ?: "cfr",
                ),
            ),
        )
    }
}

private fun registerPatchTools(context: LiteContext, service: BytecodePatchService) {
    context.tools.register(
        ToolDefinition(
            "patch_catalog",
            "List supported bytecode patch operations, schema version, and safety limits.",
            schema(),
        ),
    ) {
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<BytecodePatchCatalogResult>(),
                service.catalog(),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "method_patch_draft",
            "Validate one method patch request against anchor, opcode, and method fingerprint without rewriting bytecode.",
            patchToolSchema("draft"),
            envelope = ToolEnvelope.REQUEST,
        ),
    ) { arguments ->
        val request = decodePatchRequest(arguments)
        val inputSha256 = patchInputSha256(arguments, request)
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<BytecodePatchDraftResult>(),
                service.draft(
                    projectId = requiredString(arguments, "projectId"),
                    request = request.copy(inputSha256 = inputSha256),
                ),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "method_patch_preview",
            "Apply one method patch to an in-memory class copy and return before/after instruction evidence without writing to disk.",
            patchToolSchema("preview"),
            envelope = ToolEnvelope.REQUEST,
        ),
    ) { arguments ->
        val request = decodePatchRequest(arguments)
        val inputSha256 = patchInputSha256(arguments, request)
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<BytecodePatchPreviewResult>(),
                service.preview(
                    projectId = requiredString(arguments, "projectId"),
                    request = request.copy(inputSha256 = inputSha256),
                ),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "apply_method_patch",
            "Write one verified method patch into a new artifact and project. The source archive is never modified.",
            patchToolSchema("apply"),
            envelope = ToolEnvelope.REQUEST,
        ),
    ) { arguments ->
        val request = decodePatchRequest(arguments)
        val inputSha256 = patchInputSha256(arguments, request)
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<BytecodePatchApplyResult>(),
                service.applyMethod(
                    projectId = requiredString(arguments, "projectId"),
                    applyRequest = BytecodePatchApplyRequest(
                        projectId = requiredString(arguments, "projectId"),
                        previewId = requiredString(arguments, "previewId"),
                        inputSha256 = inputSha256,
                        request = request.copy(inputSha256 = inputSha256),
                    ),
                ),
            ),
        )
    }
}

private fun patchToolSchema(mode: String): JsonObject {
    val requiresHashes = mode != "draft"
    return buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("projectId", patchStringSchema())
            put("inputSha256", patchHashSchema())
            put("request", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("className", patchStringSchema())
                    put("methodName", patchStringSchema())
                    put("methodDescriptor", patchStringSchema())
                    put("schemaVersion", buildJsonObject {
                        put("type", "integer")
                        put("const", 2)
                        put("default", 2)
                    })
                    put("inputSha256", patchHashSchema())
                    put("methodInstructionSha256", patchHashSchema())
                    put("debugMetadataSha256", patchHashSchema())
                    put("idempotencyKey", patchStringSchema())
                    put("operations", buildJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 50)
                        put("items", buildJsonObject {
                            put("oneOf", buildJsonArray {
                                patchOperationSchemas(requiresHashes).forEach(::add)
                            })
                        })
                    })
                })
                put(
                    "required",
                    patchStringArray(
                        listOf("className", "methodName", "methodDescriptor", "operations") +
                            if (requiresHashes) {
                                listOf("inputSha256", "methodInstructionSha256", "debugMetadataSha256")
                            } else {
                                emptyList()
                            },
                    ),
                )
                put("additionalProperties", false)
            })
            if (mode == "apply") put("previewId", buildJsonObject {
                put("type", "string")
                put("pattern", "^[a-f0-9]{64}$")
            })
        })
        put(
            "required",
            patchStringArray(
                if (mode == "apply") listOf("projectId", "previewId", "request")
                else listOf("projectId", "request"),
            ),
        )
        put("additionalProperties", false)
    }
}

private fun patchOperationSchemas(requireAnchorOpcode: Boolean): List<JsonObject> = listOf(
    patchOperationSchema(
        "replace_insn",
        requireAnchorOpcode,
        arguments = buildJsonObject {
            put("oneOf", buildJsonArray {
                add(replacementArguments("simple", emptyMap(), emptyList()))
                add(replacementArguments("int", mapOf("operand" to patchStringSchema()), listOf("operand")))
                add(replacementArguments("var", mapOf("operand" to patchStringSchema()), listOf("operand")))
                add(replacementArguments("ldc", mapOf("operand" to patchStringSchema()), listOf("operand")))
                add(replacementArguments("type", mapOf("typeDescriptor" to patchStringSchema()), listOf("typeDescriptor")))
                add(replacementArguments("field", mapOf(
                    "owner" to patchStringSchema(), "name" to patchStringSchema(), "descriptor" to patchStringSchema(),
                ), listOf("owner", "name", "descriptor")))
                add(replacementArguments("method", mapOf(
                    "owner" to patchStringSchema(), "name" to patchStringSchema(), "descriptor" to patchStringSchema(),
                    "itf" to patchBooleanStringSchema(),
                ), listOf("owner", "name", "descriptor")))
                add(replacementArguments("jump", mapOf(
                    "targetInstructionIndex" to patchStringSchema(),
                ), listOf("targetInstructionIndex")))
            })
        },
    ),
    patchOperationSchema(
        "delete_insn",
        requireAnchorOpcode,
        arguments = patchObjectSchema(emptyList(), emptyMap()),
        argumentsRequired = false,
    ),
    patchOperationSchema(
        "replace_constant",
        requireAnchorOpcode,
        arguments = patchObjectSchema(
            listOf("value"),
            mapOf("value" to patchStringSchema(), "valueType" to patchEnumSchema("string", "int")),
        ),
    ),
    patchOperationSchema(
        "redirect_call",
        requireAnchorOpcode,
        arguments = patchObjectSchema(
            listOf("owner", "name", "descriptor"),
            mapOf(
                "owner" to patchStringSchema(),
                "name" to patchStringSchema(),
                "descriptor" to patchStringSchema(),
                "itf" to patchBooleanStringSchema(),
            ),
        ),
    ),
    patchOperationSchema(
        "patch_local_name",
        requireAnchorOpcode,
        arguments = patchObjectSchema(
            listOf("name"),
            mapOf("name" to patchStringSchema(), "descriptor" to patchStringSchema()),
        ),
    ),
    patchOperationSchema(
        "patch_line_table",
        requireAnchorOpcode,
        arguments = patchObjectSchema(listOf("line"), mapOf("line" to patchStringSchema())),
    ),
)

private fun patchOperationSchema(
    name: String,
    requireAnchorOpcode: Boolean,
    arguments: JsonObject,
    argumentsRequired: Boolean = true,
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("operation", buildJsonObject { put("type", "string"); put("const", name) })
        put("anchorInstructionIndex", buildJsonObject { put("type", "integer"); put("minimum", 0) })
        put("anchorOpcode", patchStringSchema())
        put("arguments", arguments)
    })
    put(
        "required",
        patchStringArray(
            listOf("operation", "anchorInstructionIndex") +
                (if (requireAnchorOpcode) listOf("anchorOpcode") else emptyList()) +
                (if (argumentsRequired) listOf("arguments") else emptyList()),
        ),
    )
    put("additionalProperties", false)
}

private fun replacementArguments(kind: String, fields: Map<String, JsonObject>, required: List<String>): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("kind", buildJsonObject { put("type", "string"); put("const", kind) })
            put("opcode", patchStringSchema())
            fields.forEach { (name, schema) -> put(name, schema) }
        })
        put("required", patchStringArray(listOf("kind", "opcode") + required))
        put("additionalProperties", false)
    }

private fun patchObjectSchema(required: List<String>, fields: Map<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { fields.forEach { (name, schema) -> put(name, schema) } })
    if (required.isNotEmpty()) put("required", patchStringArray(required))
    put("additionalProperties", false)
}

private fun patchStringSchema(): JsonObject = buildJsonObject { put("type", "string") }

private fun patchHashSchema(): JsonObject = buildJsonObject {
    put("type", "string")
    put("pattern", "^[a-f0-9]{64}$")
}

private fun patchBooleanStringSchema(): JsonObject = patchEnumSchema("true", "false")

private fun patchEnumSchema(vararg values: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("enum", patchStringArray(values.toList()))
}

private fun patchStringArray(values: List<String>) = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

private fun registerEvidenceTools(
    context: LiteContext,
    projects: ProjectRepository,
    indexes: JvmIndexService,
) {
    val service = BytecodeEvidenceService(projects, indexes)

    context.tools.register(
        ToolDefinition(
            "method_bytecode",
            "Return bounded instruction evidence for one JVM method.",
            schema(
                required = listOf("projectId", "className", "methodName"),
                properties = mapOf(
                    "methodDescriptor" to "string",
                ),
            ),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<MethodBytecodeResult>(),
                service.methodBytecode(
                    projectId = requiredString(arguments, "projectId"),
                    className = requiredString(arguments, "className"),
                    methodName = requiredString(arguments, "methodName"),
                    descriptor = optionalString(arguments, "methodDescriptor"),
                ),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "method_cfg",
            "Build bounded control-flow blocks, edges, and unreachable blocks for one JVM method.",
            schema(
                required = listOf("projectId", "className", "methodName"),
                properties = mapOf("methodDescriptor" to "string"),
            ),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<MethodCfgResult>(),
                service.methodCfg(
                    projectId = requiredString(arguments, "projectId"),
                    className = requiredString(arguments, "className"),
                    methodName = requiredString(arguments, "methodName"),
                    descriptor = optionalString(arguments, "methodDescriptor"),
                ),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "method_stack_slice",
            "Trace bounded consumed operand producers, origins and slot widths before one instruction.",
            schema(
                required = listOf("projectId", "className", "methodName", "instructionIndex"),
                properties = mapOf(
                    "methodDescriptor" to "string",
                    "argumentSlots" to "integer",
                ),
            ),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<MethodStackSliceResult>(),
                service.methodStackSlice(
                    projectId = requiredString(arguments, "projectId"),
                    className = requiredString(arguments, "className"),
                    methodName = requiredString(arguments, "methodName"),
                    descriptor = optionalString(arguments, "methodDescriptor"),
                    targetInstructionIndex = requiredInt(arguments, "instructionIndex"),
                    argumentSlots = optionalInt(arguments, "argumentSlots"),
                ),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "cfg_constant_propagation",
            "Compute conservative per-block constant and branch facts without executing target code.",
            schema(
                required = listOf("projectId", "className", "methodName"),
                properties = mapOf("methodDescriptor" to "string"),
            ),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<ConstantPropagationResult>(),
                service.cfgConstantPropagation(
                    projectId = requiredString(arguments, "projectId"),
                    className = requiredString(arguments, "className"),
                    methodName = requiredString(arguments, "methodName"),
                    descriptor = optionalString(arguments, "methodDescriptor"),
                ),
            ),
        )
    }
}

private fun registerFinderTools(context: LiteContext, indexes: JvmIndexService) {
    val service = EvidenceFinderService(indexes)
    val finders = listOf(
        "find_number_secrets" to "number-secrets",
        "find_string_transforms" to "string-transforms",
        "find_opaque_predicates" to "opaque-predicates",
        "find_control_flow_flattening" to "control-flow-flattening",
        "find_invokedynamic_dispatch" to "invokedynamic-dispatch",
    )

    finders.forEach { (toolName, scope) ->
        context.tools.register(
            ToolDefinition(
                toolName,
                "Return bounded static obfuscation evidence candidates for $scope.",
                schema(
                    required = listOf("projectId"),
                    properties = mapOf(
                        "limit" to "integer",
                        "initialEvidenceId" to "string",
                        "categoryFilter" to "array",
                    ),
                ),
            ),
        ) { arguments ->
            ToolResult.success(
                analysisJson.encodeToString(
                    serializer<FinderResult>(),
                    service.find(
                        projectId = requiredString(arguments, "projectId"),
                        categoryScope = scope,
                        limit = optionalInt(arguments, "limit")
                            ?.also { value -> require(value > 0) { "'limit' must be greater than zero, received $value." } }
                            ?: EvidenceFinderService.DEFAULT_LIMIT,
                        initialEvidenceId = optionalString(arguments, "initialEvidenceId"),
                        categoryFilter = optionalStringList(arguments, "categoryFilter"),
                    ),
                ),
            )
        }
    }

    context.tools.register(
        ToolDefinition(
            "finder_catalog",
            "Publish the finder/category vocabulary, including accepted categoryFilter values and subpatterns.",
            schema(),
        ),
    ) {
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<FinderCatalogResult>(),
                service.catalog(),
            ),
        )
    }

    context.tools.register(
        ToolDefinition(
            "evidence_summary",
            "Aggregate bounded static evidence candidates across all Lite finder categories.",
            schema(
                required = listOf("projectId"),
                properties = mapOf(
                    "limit" to "integer",
                    "categoryFilter" to "array",
                ),
            ),
        ),
    ) { arguments ->
        ToolResult.success(
            analysisJson.encodeToString(
                serializer<FinderResult>(),
                service.summary(
                    requiredString(arguments, "projectId"),
                    optionalInt(arguments, "limit")
                        ?.also { value -> require(value > 0) { "'limit' must be greater than zero, received $value." } }
                        ?: EvidenceFinderService.DEFAULT_LIMIT,
                    optionalStringList(arguments, "categoryFilter"),
                ),
            ),
        )
    }
}

private fun LiteContext.indexes(): JvmIndexService =
    resource(JvmIndexService::class.java)
        ?: throw IllegalStateException("JVM index service is not registered.")

/**
 * Decode the nested patch `request` envelope with explicit errors. The previous implementation
 * raised a bare `Key request is missing in the map.` and silently accepted misspelled keys.
 */
private fun patchInputSha256(
    arguments: kotlinx.serialization.json.JsonObject?,
    request: BytecodePatchRequest,
): String? {
    val outer = optionalString(arguments, "inputSha256")
    val inner = request.inputSha256
    require(outer == null || inner == null || outer == inner) {
        "Outer inputSha256 does not match request.inputSha256."
    }
    return outer ?: inner
}

private fun decodePatchRequest(arguments: kotlinx.serialization.json.JsonObject?): BytecodePatchRequest {
    val raw = arguments?.get("request")
        ?: throw ToolInputException(
            toolName = "method_patch",
            parameter = "request",
            message = "Missing required object argument 'request'.",
            requiredParameters = listOf("projectId", "request"),
            acceptedParameters = listOf("projectId", "request", "inputSha256", "previewId"),
            envelope = ToolEnvelope.REQUEST,
        )
    val request = raw as? kotlinx.serialization.json.JsonObject
        ?: throw ToolInputException(
            toolName = "method_patch",
            parameter = "request",
            message = "'request' must be a JSON object.",
            requiredParameters = listOf("request"),
            acceptedParameters = listOf("projectId", "request", "inputSha256", "previewId"),
            envelope = ToolEnvelope.REQUEST,
        )
    val accepted = setOf(
        "className",
        "methodName",
        "methodDescriptor",
        "schemaVersion",
        "operations",
        "inputSha256",
        "methodInstructionSha256",
        "debugMetadataSha256",
        "idempotencyKey",
    )
    val unknown = request.keys.filterNot { it in accepted }
    if (unknown.isNotEmpty()) {
        throw ToolInputException(
            toolName = "method_patch",
            parameter = unknown.first(),
            message = "Unknown request field(s) ${unknown.joinToString()}.",
            requiredParameters = listOf("className", "methodName", "methodDescriptor", "operations"),
            acceptedParameters = accepted.toList(),
            envelope = ToolEnvelope.REQUEST,
        )
    }
    return analysisJson.decodeFromJsonElement(serializer<BytecodePatchRequest>(), request)
}
