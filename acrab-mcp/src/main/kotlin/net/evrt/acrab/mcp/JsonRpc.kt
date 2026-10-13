package net.evrt.acrab.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val ParseErrorCode = -32700
internal const val InvalidRequestErrorCode = -32600
internal const val MethodNotFoundErrorCode = -32601
internal const val InvalidParamsErrorCode = -32602
internal const val InternalErrorCode = -32603
internal const val HeaderMismatchErrorCode = -32020
internal const val UnsupportedProtocolVersionErrorCode = -32022

internal data class JsonRpcRequest(
    val id: JsonElement?,
    val method: String,
    val params: JsonObject?,
) {
    val isNotification: Boolean
        get() = id == null
}

internal class JsonRpcException(
    val code: Int,
    message: String,
    val data: JsonObject? = null,
    val requestId: JsonElement? = null,
) : RuntimeException(message)

internal object JsonRpc {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(line: String): JsonRpcRequest {
        val value = try { json.parseToJsonElement(line) } catch (_: Exception) {
            throw JsonRpcException(ParseErrorCode, "Invalid JSON.")
        }
        // The tree parser retains unquoted literals; reject those outside JSON's grammar.
        val pending = ArrayDeque<JsonElement>().apply { add(value) }
        while (pending.isNotEmpty()) {
            when (val element = pending.removeLast()) {
                is JsonObject -> pending.addAll(element.values)
                is JsonArray -> pending.addAll(element)
                is JsonPrimitive -> if (!element.isString && element.content !in setOf("true", "false", "null") &&
                    !JSON_NUMBER.matches(element.content)) throw JsonRpcException(ParseErrorCode, "Invalid JSON literal.")
            }
        }
        val request = value as? JsonObject
            ?: throw JsonRpcException(InvalidRequestErrorCode, "Request must be a JSON object.")
        val id = request["id"]
        requireValidId(id)
        if (request["jsonrpc"] != JsonPrimitive("2.0")) {
            throw JsonRpcException(InvalidRequestErrorCode, "jsonrpc must be '2.0'.", requestId = id)
        }
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: throw JsonRpcException(InvalidRequestErrorCode, "Missing string method.", requestId = id)
        if (request.containsKey("params") && request["params"] !is JsonObject) {
            throw JsonRpcException(InvalidParamsErrorCode, "params must be an object.", requestId = id)
        }
        if (request.containsKey("result") || request.containsKey("error")) {
            throw JsonRpcException(InvalidRequestErrorCode, "Expected a request or notification.", requestId = id)
        }

        return JsonRpcRequest(
            id = id,
            method = method,
            params = request["params"] as? JsonObject,
        )
    }

    fun errorResponse(id: JsonElement?, code: Int, message: String, data: JsonObject? = null,
        omitId: Boolean = false): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        if (!omitId) put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
            if (data != null) put("data", data)
        })
    }

    internal fun requireValidId(id: JsonElement?) {
        when (id) {
            null -> Unit
            is JsonPrimitive -> if (!id.isString && !INTEGER_ID.matches(id.content)) {
                throw JsonRpcException(
                    InvalidRequestErrorCode,
                    "id must be a string or integer number, never null.",
                )
            }
            else -> throw JsonRpcException(
                InvalidRequestErrorCode,
                "id must be a string or integer number, never null.",
            )
        }
    }

    private val INTEGER_ID = Regex("-?(0|[1-9][0-9]*)")
    private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
