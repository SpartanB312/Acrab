package net.evrt.antarelite.mcp

import kotlinx.serialization.json.*
import net.evrt.antarelite.core.*
import java.nio.file.Files
import kotlin.test.*

class McpProtocolTest {
    @Test fun `envelopes are strict and integer ids are not limited to Int32`() {
        for (body in listOf(
            """{"id":1,"method":"ping"}""",
            """{"jsonrpc":"1.0","id":1,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":null,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":true,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":1.5,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":1,"method":7}""",
            """[]""",
        )) assertEquals(InvalidRequestErrorCode, assertFailsWith<JsonRpcException> { JsonRpc.parse(body) }.code)
        assertEquals(InvalidParamsErrorCode, assertFailsWith<JsonRpcException> {
            JsonRpc.parse("""{"jsonrpc":"2.0","id":5,"method":"ping","params":[]}""")
        }.code)
        assertEquals(ParseErrorCode, assertFailsWith<JsonRpcException> { JsonRpc.parse("{") }.code)
        assertEquals("2147483648", JsonRpc.parse("""{"jsonrpc":"2.0","id":2147483648,"method":"ping"}""").id.toString())
    }

    @Test fun `legacy handshake negotiates a real version and requires readiness`() = context { context ->
        val protocol = McpProtocol(context)
        val initialization = JsonRpc.parse("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1900-01-01","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")
        assertEquals("2025-11-25", protocol.dispatch(initialization)["protocolVersion"]!!.jsonPrimitive.content)
        assertFailsWith<JsonRpcException> { protocol.dispatch(JsonRpc.parse("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")) }
        protocol.dispatch(JsonRpc.parse("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertTrue(protocol.dispatch(JsonRpc.parse("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")).containsKey("tools"))
        assertFailsWith<JsonRpcException> { protocol.dispatch(initialization) }
    }

    @Test fun `modern discovery and calls are independent of initialization`() = context { context ->
        context.tools.register(ToolDefinition("echo", "Echo.", JsonObject(emptyMap()))) { ToolResult.success("ok") }
        val protocol = McpProtocol(context)
        val discover = protocol.dispatch(modern("server/discover"))
        assertEquals("complete", discover["resultType"]!!.jsonPrimitive.content)
        assertTrue(discover["supportedVersions"]!!.jsonArray.any { it.jsonPrimitive.content == MODERN_PROTOCOL_VERSION })
        assertEquals(1, protocol.dispatch(modern("tools/list"))["tools"]!!.jsonArray.size)
        assertEquals("ok", protocol.dispatch(modern("tools/call", "\"name\":\"echo\","))["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(protocol.negotiatedVersion)
        assertEquals(UnsupportedProtocolVersionErrorCode, assertFailsWith<JsonRpcException> {
            protocol.dispatch(modern("tools/list", version = "2099-01-01"))
        }.code)
        assertEquals(InvalidParamsErrorCode, assertFailsWith<JsonRpcException> {
            protocol.dispatch(JsonRpc.parse("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"$PROTOCOL_VERSION_META":"$MODERN_PROTOCOL_VERSION"}}}"""))
        }.code)
    }

    @Test fun `notifications cannot invoke tools or initialize a session`() = context { context ->
        var called = false
        context.tools.register(ToolDefinition("echo", "Echo.", JsonObject(emptyMap()))) { called = true; ToolResult.success("ok") }
        val protocol = McpProtocol(context)
        assertFailsWith<JsonRpcException> { protocol.dispatch(JsonRpc.parse("""{"jsonrpc":"2.0","method":"tools/call","params":{"name":"echo"}}""")) }
        assertFalse(called)
        assertFailsWith<JsonRpcException> { protocol.dispatch(JsonRpc.parse("""{"jsonrpc":"2.0","method":"initialize"}""")) }
        assertNull(protocol.negotiatedVersion)
    }

    private fun modern(method: String, extra: String = "", version: String = MODERN_PROTOCOL_VERSION) = JsonRpc.parse(
        """{"jsonrpc":"2.0","id":1,"method":"$method","params":{$extra"_meta":{"$PROTOCOL_VERSION_META":"$version","$CLIENT_CAPABILITIES_META":{}}}}""")

    private fun context(block: (LiteContext) -> Unit) {
        val workspace = Files.createTempDirectory("antares-protocol-test")
        try { LiteContext(LiteConfig(workspacePath = workspace.toString())).use(block) }
        finally { Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
