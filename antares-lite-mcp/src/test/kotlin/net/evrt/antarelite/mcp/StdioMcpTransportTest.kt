package net.evrt.antarelite.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.evrt.antarelite.core.LiteConfig
import net.evrt.antarelite.core.LiteContext
import net.evrt.antarelite.core.ToolDefinition
import net.evrt.antarelite.core.ToolResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StdioMcpTransportTest {
    @Test
    fun `initialize list call and ping produce protocol responses`() {
        val context = LiteContext(LiteConfig())
        context.tools.register(
            ToolDefinition("echo", "Echoes a value.", echoSchema()),
        ) { arguments ->
            ToolResult.success("value=${arguments?.get("value")}")
        }
        val inputText = listOf(
            line(1, "initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }),
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            line(2, "tools/list", null),
            line(3, "tools/call", buildJsonObject { put("name", "echo"); put("arguments", buildJsonObject { put("value", "ok") }) }),
            line(4, "ping", JsonObject(emptyMap())),
        ).joinToString("\n") + "\n"
        val output = ByteArrayOutputStream()

        StdioMcpTransport(context, ByteArrayInputStream(inputText.toByteArray()), output).use { transport ->
            transport.serve()
        }

        val responses = output.toString().trim().lines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(4, responses.size)
        assertEquals(1, responses[0]["id"]?.jsonPrimitive?.content?.toInt())
        assertEquals("2025-06-18", responses[0]["result"]?.jsonObject?.get("protocolVersion")?.jsonPrimitive?.content)
        assertEquals(1, responses[1]["result"]?.jsonObject?.get("tools")?.jsonArray?.size)
        assertEquals(3, responses[2]["id"]?.jsonPrimitive?.content?.toInt())
        assertEquals(
            """value="ok"""",
            responses[2]["result"]?.jsonObject?.get("content")?.jsonArray?.get(0)?.jsonObject?.get("text")
                ?.jsonPrimitive?.content,
        )
        assertTrue(responses[3]["result"]?.jsonObject?.isEmpty() == true)
    }

    @Test
    fun `protocol and tool failures return JSON-RPC errors`() {
        val context = LiteContext(LiteConfig())
        context.tools.register(
            ToolDefinition("noop", "Does nothing.", echoSchema()),
        ) { ToolResult.success("ok") }
        val inputText = listOf(
            "not-json",
            line(1, "initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }),
            line(2, "tools/list", JsonObject(emptyMap())),
            line(4, "not-a-method", JsonObject(emptyMap())),
            line(3, "tools/call", buildJsonObject { put("name", "missing") }),
        ).joinToString("\n") + "\n"
        val output = ByteArrayOutputStream()

        StdioMcpTransport(context, ByteArrayInputStream(inputText.toByteArray()), output).use { transport ->
            transport.serve()
        }

        val responses = output.toString().trim().lines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(5, responses.size)
        assertEquals(-32600, responses[0]["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content?.toInt())
        assertTrue(responses[2]["result"]?.jsonObject?.containsKey("tools") == true)
        assertEquals(-32601, responses[3]["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content?.toInt())
        assertEquals(-32602, responses[4]["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `argument failures are invalid-params errors naming the parameter and envelope`() {
        val context = LiteContext(LiteConfig())
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("methodDescriptor", buildJsonObject { put("type", "string") }) })
        }
        context.tools.register(ToolDefinition("resolve", "Resolves.", schema)) { ToolResult.success("ok") }
        val inputText = listOf(
            line(1, "initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }),
            line(2, "tools/call", buildJsonObject {
                put("name", "resolve")
                put("arguments", buildJsonObject { put("descriptor", "()V") })
            }),
        ).joinToString("\n") + "\n"
        val output = ByteArrayOutputStream()

        StdioMcpTransport(context, ByteArrayInputStream(inputText.toByteArray()), output).use { transport ->
            transport.serve()
        }

        val responses = output.toString().trim().lines().map { Json.parseToJsonElement(it).jsonObject }
        val error = responses[1]["error"]!!.jsonObject
        assertEquals(-32602, error["code"]!!.jsonPrimitive.content.toInt())
        val message = error["message"]!!.jsonPrimitive.content
        assertTrue(message.contains("descriptor"), message)
        assertTrue(message.contains("methodDescriptor"), message)
        assertTrue(message.contains("Accepted envelope"), message)
    }

    private fun line(id: Int, method: String, params: JsonObject?): String {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        return Json.encodeToString(JsonObject.serializer(), request)
    }

    private fun echoSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("value", buildJsonObject { put("type", "string") }) })
    }

}
