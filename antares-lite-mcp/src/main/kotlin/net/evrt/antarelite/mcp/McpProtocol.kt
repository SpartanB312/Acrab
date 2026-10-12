package net.evrt.antarelite.mcp

import kotlinx.serialization.json.*
import net.evrt.antarelite.core.LiteContext
import net.evrt.antarelite.core.ToolInputException
import net.evrt.antarelite.core.UnknownToolException

internal const val MODERN_PROTOCOL_VERSION = "2026-07-28"
internal val LEGACY_PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
internal val SUPPORTED_PROTOCOL_VERSIONS = listOf(MODERN_PROTOCOL_VERSION) + LEGACY_PROTOCOL_VERSIONS
internal const val PROTOCOL_VERSION_META = "io.modelcontextprotocol/protocolVersion"
internal const val CLIENT_CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities"

/** Legacy state belongs to one session; modern requests carry their own context. */
internal class McpProtocol(private val context: LiteContext) {
    @Volatile var negotiatedVersion: String? = null
        private set
    @Volatile private var ready = false

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun parseLine(line: String): JsonRpcRequest = JsonRpc.parse(line)

    fun dispatch(request: JsonRpcRequest, modern: Boolean = hasModernMetadata(request)): JsonObject {
        if (request.params?.containsKey("_meta") == true && request.params["_meta"] !is JsonObject) {
            throw JsonRpcException(InvalidParamsErrorCode, "_meta must be an object.")
        }
        if (modern) validateModernMetadata(request)
        if (request.isNotification && request.method != "notifications/initialized") {
            throw JsonRpcException(InvalidRequestErrorCode, "This method requires a request id.")
        }
        val result = when (request.method) {
        "initialize" -> {
            if (modern) throw JsonRpcException(MethodNotFoundErrorCode, "Use server/discover for this protocol version.")
            initialize(request)
        }
        "notifications/initialized" -> {
            if (modern || !request.isNotification) throw JsonRpcException(InvalidRequestErrorCode, "Expected a legacy initialized notification.")
            requireInitialized()
            ready = true
            JsonObject(emptyMap())
        }
        "server/discover" -> {
            if (!modern) throw JsonRpcException(MethodNotFoundErrorCode, "server/discover requires per-request metadata.")
            buildJsonObject {
                put("supportedVersions", JsonArray(SUPPORTED_PROTOCOL_VERSIONS.map(::JsonPrimitive)))
                put("capabilities", capabilities())
            }
        }
        "ping" -> {
            if (modern) throw JsonRpcException(MethodNotFoundErrorCode, "ping is not defined in this protocol version.")
            JsonObject(emptyMap())
        }
        "tools/list" -> {
            if (!modern) requireReady()
            buildJsonObject {
                put("tools", buildJsonArray {
                    context.tools.definitions().forEach { definition ->
                        add(buildJsonObject {
                            put("name", definition.name)
                            put("description", definition.description)
                            put("inputSchema", definition.inputSchema)
                        })
                    }
                })
            }
        }
        "tools/call" -> {
            if (!modern) requireReady()
            callTool(request.params)
        }
        else -> throw JsonRpcException(MethodNotFoundErrorCode, "Method not found: ${request.method}")
        }
        return if (!modern) result else JsonObject(result + mapOf(
            "resultType" to JsonPrimitive("complete"),
            "_meta" to buildJsonObject { put("io.modelcontextprotocol/serverInfo", serverInfo()) },
        ))
    }

    fun successResponse(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    fun errorResponse(id: JsonElement?, code: Int, message: String, data: JsonObject? = null): JsonObject =
        JsonRpc.errorResponse(id, code, message, data)

    fun encode(value: JsonObject): String = Json.encodeToString(JsonObject.serializer(), value)

    @Synchronized private fun initialize(request: JsonRpcRequest): JsonObject {
        if (negotiatedVersion != null) throw JsonRpcException(InvalidRequestErrorCode, "The session is already initialized.")
        val params = request.params
            ?: throw JsonRpcException(InvalidParamsErrorCode, "Missing params.")
        val requested = string(params, "protocolVersion")
        requireObject(params, "capabilities")
        validateImplementation(requireObject(params, "clientInfo"))
        val protocolVersion = requested.takeIf { it in LEGACY_PROTOCOL_VERSIONS } ?: LEGACY_PROTOCOL_VERSIONS.first()
        negotiatedVersion = protocolVersion

        return buildJsonObject {
            put("protocolVersion", protocolVersion)
            put("capabilities", capabilities())
            put("serverInfo", serverInfo())
        }
    }

    private fun requireInitialized() {
        if (negotiatedVersion == null) {
            throw JsonRpcException(InvalidRequestErrorCode, "The connection is not initialized.")
        }
    }

    private fun requireReady() {
        requireInitialized()
        if (!ready) throw JsonRpcException(InvalidRequestErrorCode, "Send notifications/initialized before tool requests.")
    }

    private fun capabilities() = buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) }
    private fun serverInfo() = buildJsonObject {
        put("name", context.config.serverName)
        put("version", context.config.serverVersion)
    }

    private fun callTool(params: JsonObject?): JsonObject {
        val request = params ?: throw JsonRpcException(InvalidParamsErrorCode, "Missing params.")
        val name = string(request, "name")
        if (request.containsKey("arguments") && request["arguments"] !is JsonObject) {
            throw JsonRpcException(InvalidParamsErrorCode, "arguments must be an object.")
        }
        val arguments = request["arguments"] as? JsonObject
        val result = try {
            context.tools.call(name, arguments)
        } catch (failure: UnknownToolException) {
            throw JsonRpcException(InvalidParamsErrorCode, failure.message ?: "Unknown tool.")
        } catch (failure: ToolInputException) {
            // Argument-level failures are caller errors, not internal faults.
            throw JsonRpcException(InvalidParamsErrorCode, failure.message ?: "Invalid arguments.")
        }

        return buildJsonObject {
            put("content", buildJsonArray {
                result.content.forEach { content -> add(buildJsonObject {
                    put("type", "text")
                    put("text", content.text)
                }) }
            })
            put("isError", result.isError)
        }
    }

    companion object {
        fun hasModernMetadata(request: JsonRpcRequest): Boolean =
            (request.params?.get("_meta") as? JsonObject)?.containsKey(PROTOCOL_VERSION_META) == true

        fun validateModernMetadata(request: JsonRpcRequest): String {
            val meta = requireObject(request.params ?: JsonObject(emptyMap()), "_meta")
            val version = string(meta, PROTOCOL_VERSION_META)
            if (version != MODERN_PROTOCOL_VERSION) throw JsonRpcException(
                UnsupportedProtocolVersionErrorCode, "Unsupported protocol version.", buildJsonObject {
                    put("requested", version)
                    put("supported", JsonArray(SUPPORTED_PROTOCOL_VERSIONS.map(::JsonPrimitive)))
                })
            requireObject(meta, CLIENT_CAPABILITIES_META)
            meta["io.modelcontextprotocol/clientInfo"]?.let {
                validateImplementation(it as? JsonObject ?: throw JsonRpcException(InvalidParamsErrorCode, "clientInfo must be an object."))
            }
            return version
        }

        private fun validateImplementation(info: JsonObject) {
            string(info, "name")
            string(info, "version")
        }

        private fun requireObject(value: JsonObject, key: String): JsonObject = value[key] as? JsonObject
            ?: throw JsonRpcException(InvalidParamsErrorCode, "$key must be an object.")

        private fun string(value: JsonObject, key: String): String =
            (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?: throw JsonRpcException(InvalidParamsErrorCode, "$key must be a nonblank string.")
    }
}


