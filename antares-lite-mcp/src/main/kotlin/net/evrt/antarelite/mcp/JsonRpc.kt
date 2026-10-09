package net.evrt.antarelite.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val ParseErrorCode = -32700
internal const val InvalidRequestErrorCode = -32600
internal const val MethodNotFoundErrorCode = -32601
internal const val InvalidParamsErrorCode = -32602
internal const val InternalErrorCode = -32603

internal data class JsonRpcRequest(
    val id: JsonElement?,
    val method: String,
    val params: JsonObject?,
) {
    val isNotification: Boolean
        get() = id == null || id is JsonNull
}

internal class JsonRpcException(
    val code: Int,
    message: String,
) : RuntimeException(message)

internal object JsonRpc {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(line: String): JsonRpcRequest {
        val value = json.parseToJsonElement(line)
        val request = value as? JsonObject
            ?: throw JsonRpcException(InvalidRequestErrorCode, "Request must be a JSON object.")
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull
            ?: throw JsonRpcException(InvalidRequestErrorCode, "Missing string method.")
        val id = request["id"]
        requireValidId(id)

        return JsonRpcRequest(
            id = id,
            method = method,
            params = request["params"] as? JsonObject,
        )
    }

    fun errorResponse(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun requireValidId(id: JsonElement?) {
        when (id) {
            null, is JsonNull -> Unit
            is JsonPrimitive -> if (!id.isString && id.contentOrNull?.toIntOrNull() == null) {
                throw JsonRpcException(
                    InvalidRequestErrorCode,
                    "id must be a string or integer number.",
                )
            }
            else -> throw JsonRpcException(
                InvalidRequestErrorCode,
                "id must be a string, integer number, or null.",
            )
        }
    }
}
