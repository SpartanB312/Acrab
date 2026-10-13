package net.evrt.acrab.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolRegistryTest {
    @Test
    fun `keeps registration order and exposes definitions`() {
        val registry = ToolRegistry()
        val schema = buildJsonObject { put("type", "object") }
        registry.register(ToolDefinition("alpha", "First tool.", schema)) { ToolResult.success("a") }
        registry.register(ToolDefinition("beta", "Second tool.", schema)) { ToolResult.success("b") }

        assertEquals(listOf("alpha", "beta"), registry.definitions().map(ToolDefinition::name))
    }

    @Test
    fun `rejects duplicate tool names`() {
        val registry = ToolRegistry()
        val schema = buildJsonObject { put("type", "object") }
        registry.register(ToolDefinition("same", "First.", schema)) { ToolResult.success("one") }

        assertFailsWith<IllegalStateException> {
            registry.register(ToolDefinition("same", "Second.", schema)) { ToolResult.success("two") }
        }
    }

    @Test
    fun `normalizes missing arguments and returns tool results`() {
        val registry = ToolRegistry()
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("value", buildJsonObject { put("type", "string") }) })
        }
        registry.register(ToolDefinition("echo", "Echo arguments.", schema)) { arguments ->
            ToolResult.success(arguments?.get("value")?.let { (it as? JsonPrimitive)?.content } ?: "empty")
        }

        assertEquals("hello", registry.call("echo", buildJsonObject { put("value", "hello") }).content.single().text)
        assertEquals("empty", registry.call("echo", null).content.single().text)
    }

    @Test
    fun `rejects unknown arguments with the accepted envelope`() {
        val registry = ToolRegistry()
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("methodDescriptor", buildJsonObject { put("type", "string") }) })
        }
        registry.register(ToolDefinition("resolution", "Resolves a method.", schema)) { ToolResult.success("ok") }

        val failure = assertFailsWith<ToolInputException> {
            registry.call("resolution", buildJsonObject { put("descriptor", "()V") })
        }
        assertTrue(failure.message!!.contains("descriptor"))
        assertTrue(failure.message!!.contains("methodDescriptor"))
        assertEquals(listOf("methodDescriptor"), failure.acceptedParameters)
    }

    @Test
    fun `rejects a missing required argument`() {
        val registry = ToolRegistry()
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("projectId", buildJsonObject { put("type", "string") }) })
            put("required", buildJsonArray { add(JsonPrimitive("projectId")) })
        }
        registry.register(ToolDefinition("summary", "Summarizes.", schema)) { ToolResult.success("ok") }

        assertFailsWith<ToolInputException> { registry.call("summary", buildJsonObject { }) }
        assertFailsWith<ToolInputException> { registry.call("summary", null) }
    }

    @Test
    fun `reports unknown tools`() {
        assertFailsWith<UnknownToolException> {
            ToolRegistry().call("missing", null)
        }.also { failure ->
            assertEquals("missing", failure.toolName)
        }
    }
}

