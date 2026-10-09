package net.evrt.antarelite.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.evrt.antarelite.core.LiteConfig
import net.evrt.antarelite.core.LiteContext
import net.evrt.antarelite.core.ToolDefinition
import net.evrt.antarelite.core.ToolResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpMcpTransportTest {
    @Test
    fun `http sessions initialize list and call tools`() {
        LiteContext(LiteConfig(workspacePath = createWorkspace(), httpPort = 0)).use { context ->
            context.tools.register(
                ToolDefinition("echo", "Echoes a value.", valueSchema()),
            ) { arguments -> ToolResult.success("value=${arguments?.get("value")}") }

            HttpMcpTransport(context, context.config).use { transport ->
                transport.start()
                val port = transport.port
                val endpoint = "http://127.0.0.1:$port/mcp"
                val client = HttpClient.newHttpClient()

                val initialized = post(
                    client,
                    endpoint,
                    request(1, "initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }),
                )
                val session = initialized.headers().firstValue("Mcp-Session-Id").orElse("")
                assertEquals("2025-06-18", result(initialized.body())["protocolVersion"]?.jsonPrimitive?.content)

                val listed = post(
                    client,
                    endpoint,
                    request(2, "tools/list", null),
                    session,
                )
                assertEquals(1, result(listed.body())["tools"]?.jsonArray?.size)

                val called = post(
                    client,
                    endpoint,
                    request(
                        3,
                        "tools/call",
                        buildJsonObject {
                            put("name", "echo")
                            put("arguments", buildJsonObject { put("value", "http") })
                        },
                    ),
                    session,
                )
                assertEquals("value=\"http\"", content(called.body()))
                assertTrue(session.isNotBlank())
            }
        }
    }

    @Test
    fun `health returns ready and unlisted mcp requests require initialization`() {
        LiteContext(LiteConfig(workspacePath = createWorkspace(), httpPort = 0)).use { context ->
            context.tools.register(
                ToolDefinition("noop", "Does nothing.", valueSchema()),
            ) { ToolResult.success("ok") }

            HttpMcpTransport(context, context.config).use { transport ->
                transport.start()
                val client = HttpClient.newHttpClient()
                val health = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${transport.port}/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                )
                assertEquals(200, health.statusCode())
                assertTrue(health.body().contains("\"status\": \"ready\""))

                val uninitialized = post(
                    client,
                    "http://127.0.0.1:${transport.port}/mcp",
                    request(1, "tools/list", null),
                )
                assertEquals(200, uninitialized.statusCode())
                assertEquals(-32600, error(uninitialized.body()))
            }
        }
    }

    @Test
    fun `http tool argument failures return structured invalid-params errors`() {
        LiteContext(LiteConfig(workspacePath = createWorkspace(), httpPort = 0)).use { context ->
            context.tools.register(
                ToolDefinition("echo", "Echoes a value.", valueSchema()),
            ) { arguments -> ToolResult.success("value=${arguments?.get("value")}") }

            HttpMcpTransport(context, context.config).use { transport ->
                transport.start()
                val client = HttpClient.newHttpClient()
                val endpoint = "http://127.0.0.1:${transport.port}/mcp"
                val initialized = post(
                    client,
                    endpoint,
                    request(1, "initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }),
                )
                val session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow()
                val rejected = post(
                    client,
                    endpoint,
                    request(2, "tools/call", buildJsonObject { put("name", "echo") }),
                    session,
                )

                assertEquals(200, rejected.statusCode())
                assertEquals(-32602, error(rejected.body()))
                assertTrue(rejected.body().contains("value"), rejected.body())
                assertTrue(rejected.body().contains("Accepted envelope"), rejected.body())
            }
        }
    }

    private fun post(
        client: HttpClient,
        endpoint: String,
        body: JsonObject,
        session: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(JsonObject.serializer(), body)))
        if (session != null) builder.header("Mcp-Session-Id", session)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    private fun request(id: Int, method: String, params: JsonObject?): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }

    private fun result(body: String): JsonObject =
        Json.parseToJsonElement(body).jsonObject["result"]!!.jsonObject

    private fun content(body: String): String =
        result(body)["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun error(body: String): Int =
        Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()

    private fun createWorkspace(): String =
        java.nio.file.Files.createTempDirectory("antares-lite-http-test").toString()

    private fun valueSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("value", buildJsonObject { put("type", "string") }) })
        put("required", buildJsonArray { add(JsonPrimitive("value")) })
    }
}







