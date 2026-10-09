package net.evrt.antarelite.analysis.support

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

val analysisJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

fun schema(
    required: List<String> = emptyList(),
    properties: Map<String, String> = emptyMap(),
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        val requiredProperties = required.associateWith { name ->
            properties[name] ?: when (name) {
                "projectId", "className", "methodName", "query", "fileName", "archiveBase64" -> "string"
                "instructionIndex" -> "integer"
                else -> error("Required parameter '$name' needs an explicit schema type.")
            }
        }
        (requiredProperties + properties).forEach { (name, type) ->
            putJsonObject(name) { put("type", type) }
        }
    }
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }
}

fun requiredString(arguments: JsonObject?, name: String): String =
    optionalString(arguments, name)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing required string argument '$name'.")

fun requiredInt(arguments: JsonObject?, name: String): Int =
    optionalInt(arguments, name) ?: throw IllegalArgumentException("Missing required integer argument '$name': expected a number.")

fun optionalString(arguments: JsonObject?, name: String): String? =
    arguments?.get(name)?.let { value ->
        value as? JsonPrimitive
    }?.contentOrNull

fun optionalInt(arguments: JsonObject?, name: String): Int? =
    arguments?.get(name)?.let { value ->
        value as? JsonPrimitive
    }
        ?.content?.toIntOrNull()

fun optionalBoolean(arguments: JsonObject?, name: String): Boolean? =
    arguments?.get(name)?.let { value ->
        value as? JsonPrimitive
    }
        ?.content?.toBooleanStrictOrNull()

/** Read a JSON array-of-strings argument, rejecting a wrong element type by name. */
fun optionalStringList(arguments: JsonObject?, name: String): List<String> {
    val value = arguments?.get(name) ?: return emptyList()
    val array = value as? kotlinx.serialization.json.JsonArray
        ?: throw IllegalArgumentException("'$name' must be an array of strings.")
    return array.map { element ->
        (element as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalArgumentException("'$name' must contain only strings.")
    }
}
