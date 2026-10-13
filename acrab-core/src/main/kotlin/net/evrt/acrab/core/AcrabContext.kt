package net.evrt.acrab.core

import kotlinx.serialization.json.JsonObject

fun interface ToolHandler {
    operator fun invoke(arguments: JsonObject?): ToolResult
}

/** Composition root and lifecycle owner for the Acrab server. */
class AcrabContext(
    val config: AcrabConfig,
) : AutoCloseable {
    val tools = ToolRegistry()
    private val resources = mutableListOf<AutoCloseable>()
    private var closed = false

    fun registerResource(resource: AutoCloseable) {
        checkOpen()
        resources += resource
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resource(type: Class<T>): T? = resources.firstOrNull { type.isInstance(it) } as? T

    override fun close() {
        if (closed) return
        closed = true
        resources.asReversed().forEach { resource ->
            runCatching { resource.close() }
        }
        resources.clear()
    }

    fun checkOpen() {
        check(!closed) { "AcrabContext is closed." }
    }
}
