package net.evrt.antarelite.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.evrt.antarelite.core.LiteContext
import net.evrt.antarelite.core.ToolInputException
import net.evrt.antarelite.core.ToolResult
import net.evrt.antarelite.core.UnknownToolException

/** One initialized JSON-RPC/MCP session. Transports own their protocol instance. */
internal class McpProtocol(private val context: LiteContext) {
    @Volatile private var initialized = false

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun parseLine(line: String): JsonRpcRequest = JsonRpc.parse(line)

    fun dispatch(request: JsonRpcRequest): JsonObject = when (request.method) {
        "initialize" -> initialize(request)
        "notifications/initialized" -> {
            requireInitialized()
            JsonObject(emptyMap())
        }
        "ping" -> JsonObject(emptyMap())
        "tools/list" -> {
            requireInitialized()
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
            requireInitialized()
            callTool(request.params)
        }
        else -> throw JsonRpcException(MethodNotFoundErrorCode, "Method not found: ${request.method}")
    }

    fun successResponse(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    fun errorResponse(id: JsonElement?, code: Int, message: String): JsonObject =
        JsonRpc.errorResponse(id, code, message)

    fun encode(value: JsonObject): String = Json.encodeToString(JsonObject.serializer(), value)

    private fun initialize(request: JsonRpcRequest): JsonObject {
        val params = request.params
            ?: throw JsonRpcException(InvalidParamsErrorCode, "Missing params.")
        val protocolVersion = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
            ?: throw JsonRpcException(InvalidParamsErrorCode, "Missing protocolVersion.")
        initialized = true

        return buildJsonObject {
            put("protocolVersion", protocolVersion)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { put("listChanged", false) })
            })
            put("serverInfo", buildJsonObject {
                put("name", context.config.serverName)
                put("version", context.config.serverVersion)
            })
        }
    }

    private fun requireInitialized() {
        if (!initialized) {
            throw JsonRpcException(InvalidRequestErrorCode, "The connection is not initialized.")
        }
    }

    private fun callTool(params: JsonObject?): JsonObject {
        val request = params ?: throw JsonRpcException(InvalidParamsErrorCode, "Missing params.")
        val name = request["name"]?.jsonPrimitive?.contentOrNull
            ?: throw JsonRpcException(InvalidParamsErrorCode, "Missing tool name.")
        val arguments = request["arguments"] as? JsonObject
        val result: ToolResult = try {
            context.tools.call(name, arguments)
        } catch (failure: UnknownToolException) {
            throw JsonRpcException(InvalidParamsErrorCode, failure.message ?: "Unknown tool.")
        } catch (failure: ToolInputException) {
            // Argument-level failures are caller errors, not internal faults.
            throw JsonRpcException(InvalidParamsErrorCode, failure.message ?: "Invalid arguments.")
        }

        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", result.content.single().text)
                })
            })
            put("isError", result.isError)
        }
    }
}


