package net.evrt.antarelite.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Argument shape a tool expects. Flat tools take top-level parameters; request tools nest one
 * `request` object that carries the method identity and operations.
 */
enum class ToolEnvelope {
    FLAT,
    REQUEST,
}

/**
 * A caller-visible argument failure that names the offending parameter and the accepted envelope
 * instead of surfacing an internal exception string.
 */
class ToolInputException(
    val toolName: String,
    val parameter: String?,
    message: String,
    val requiredParameters: List<String> = emptyList(),
    val acceptedParameters: List<String> = emptyList(),
    val envelope: ToolEnvelope = ToolEnvelope.FLAT,
) : IllegalArgumentException(message)

/** Client-facing metadata for a callable tool. */
@kotlinx.serialization.Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val envelope: ToolEnvelope = ToolEnvelope.FLAT,
) {
    init {
        require(name.isNotBlank()) { "Tool name must not be blank." }
        require(description.isNotBlank()) { "Description for tool '$name' must not be blank." }
    }

    /** Parameters the tool requires, in declaration order. */
    val requiredParameters: List<String>
        get() = inputSchema["required"].optionalStringArray()

    /** Every parameter the tool accepts: declared properties plus any required name. */
    val acceptedParameters: List<String>
        get() = (inputSchema["properties"]?.jsonObject?.keys.orEmpty() + requiredParameters).distinct()
}

private fun kotlinx.serialization.json.JsonElement?.optionalStringArray(): List<String> =
    (this as? JsonArray)?.mapNotNull { element -> runCatching { element.jsonPrimitive.contentOrNull }.getOrNull() }
        .orEmpty()

/** A successful or tool-level-failed result returned through MCP `tools/call`. */
@kotlinx.serialization.Serializable
data class ToolResult(
    val content: List<Content> = emptyList(),
    val isError: Boolean = false,
) {
    @kotlinx.serialization.Serializable
    data class Content(val text: String)

    init {
        require(content.isNotEmpty()) { "Tool result content must not be empty." }
    }

    companion object {
        fun success(text: String): ToolResult = ToolResult(listOf(Content(text)))

        fun error(text: String): ToolResult = ToolResult(listOf(Content(text)), isError = true)
    }
}

/** Raised when a caller names a tool that has not been registered. */
class UnknownToolException(val toolName: String) :
    IllegalArgumentException("Unknown tool: $toolName")

/** Ordered tool registry shared by the application and transports. */
class ToolRegistry {
    private val tools = linkedMapOf<String, ToolDefinition>()
    private val handlers = linkedMapOf<String, ToolHandler>()
    private val platformEndpoints = setOf("health", "list_tools")

    fun register(definition: ToolDefinition, handler: (JsonObject?) -> ToolResult) {
        check(definition.name !in tools) { "Tool is already registered: ${definition.name}" }
        tools[definition.name] = definition
        handlers[definition.name] = ToolHandler { arguments -> handler(arguments) }
    }

    fun definitions(): List<ToolDefinition> = tools.values.toList()

    fun analysisDefinitions(): List<ToolDefinition> = tools.values.filterNot { it.name in platformEndpoints }

    fun call(name: String, arguments: JsonObject?): ToolResult {
        val definition = tools[name] ?: throw UnknownToolException(name)
        val handler = handlers.getValue(name)
        val envelope = validateEnvelope(definition, arguments)
        return try {
            handler(envelope)
        } catch (failure: ToolInputException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            // Keep argument-level failures actionable: name the offending parameter and the
            // accepted envelope instead of leaking an internal message.
            throw reject(definition, failure.message ?: "Invalid arguments.")
        }
    }

    /**
     * Rejects an argument object that does not match the tool's declared envelope. A misspelled key
     * such as `methodDescriptor` instead of `descriptor` is a hard error rather than a silent
     * fallback to name-only resolution.
     */
    private fun validateEnvelope(definition: ToolDefinition, arguments: JsonObject?): JsonObject {
        val suppliedArguments = arguments ?: JsonObject(emptyMap())
        val accepted = definition.acceptedParameters.toSet()
        val unknown = suppliedArguments.keys.filterNot { it in accepted }
        if (unknown.isNotEmpty()) {
            val suggested = unknown.mapNotNull { key -> closestParameter(key, accepted) }.distinct()
            val hint = if (suggested.isNotEmpty()) " Did you mean: ${suggested.joinToString()}?" else ""
            throw reject(
                definition,
                "Unknown argument(s) ${unknown.joinToString()} for '${definition.name}'.$hint",
            )
        }
        val missing = definition.requiredParameters.filter { key ->
            suppliedArguments[key] == null || suppliedArguments[key] is kotlinx.serialization.json.JsonNull
        }
        if (missing.isNotEmpty()) {
            throw reject(definition, "Missing required argument(s) ${missing.joinToString()}.")
        }
        return suppliedArguments
    }

    private fun reject(definition: ToolDefinition, message: String): ToolInputException =
        ToolInputException(
            toolName = definition.name,
            parameter = null,
            message = "Invalid arguments for '${definition.name}': $message " +
                "Accepted envelope: ${describeEnvelope(definition)}",
            requiredParameters = definition.requiredParameters,
            acceptedParameters = definition.acceptedParameters,
            envelope = definition.envelope,
        )

    private fun describeEnvelope(definition: ToolDefinition): String {
        val parameters = definition.acceptedParameters.joinToString(", ").ifBlank { "none" }
        val shape = when (definition.envelope) {
            ToolEnvelope.FLAT -> "flat parameters"
            ToolEnvelope.REQUEST -> "one nested 'request' object with className, methodName, methodDescriptor, and operations"
        }
        return "$shape; accepted parameters: $parameters"
    }

    private fun closestParameter(key: String, accepted: Set<String>): String? =
        accepted
            .map { candidate ->
                val lowerKey = key.lowercase()
                val lowerCandidate = candidate.lowercase()
                // A renamed prefix such as `descriptor` for `methodDescriptor` is the common
                // mistake this check exists to catch, and edit distance alone misses it.
                val distance = if (lowerCandidate.endsWith(lowerKey) || lowerKey.endsWith(lowerCandidate)) {
                    0
                } else {
                    editDistance(lowerKey, lowerCandidate)
                }
                candidate to distance
            }
            .filter { (candidate, distance) -> distance <= maxOf(2, candidate.length / 3) }
            .minByOrNull { it.second }
            ?.first
}

private fun editDistance(left: String, right: String): Int {
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    left.forEachIndexed { row, leftChar ->
        current[0] = row + 1
        right.forEachIndexed { column, rightChar ->
            val substitution = previous[column] + if (leftChar == rightChar) 0 else 1
            current[column + 1] = minOf(substitution, previous[column + 1] + 1, current[column] + 1)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[right.length]
}
