package net.evrt.antarelite.mcp

import kotlinx.serialization.json.*
import net.evrt.antarelite.core.*
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HttpMcpBoundaryTest {
    @TempDir lateinit var workspace: Path
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @Test fun `modern requests validate headers and work without sessions`() = server { context, transport ->
        context.tools.register(ToolDefinition("echo", "Echo.", JsonObject(emptyMap()))) { ToolResult.success("ok") }
        context.tools.register(ToolDefinition("\u00e9", "Echo.", JsonObject(emptyMap()))) { ToolResult.success("ok") }
        val discovery = post(transport, modern("server/discover"), modernHeaders("server/discover"))
        assertEquals(200, discovery.statusCode())
        assertTrue(discovery.headers().firstValue("Mcp-Session-Id").isEmpty)
        assertEquals("complete", payload(discovery)["result"]!!.jsonObject["resultType"]!!.jsonPrimitive.content)
        assertEquals(200, post(transport, modern("tools/list"), modernHeaders("tools/list")).statusCode())
        val called = post(transport, modern("tools/call", buildJsonObject { put("name", "echo") }),
            modernHeaders("tools/call") + ("Mcp-Name" to "=?base64?${Base64.getEncoder().encodeToString("echo".toByteArray())}?="))
        assertEquals(200, called.statusCode())
        assertTrue(called.body().contains("event: message"))
        assertEquals("complete", payload(called)["result"]!!.jsonObject["resultType"]!!.jsonPrimitive.content)
        val unicodeCall = modern("tools/call", buildJsonObject { put("name", "\u00e9") })
        val rawName = post(transport, unicodeCall, modernHeaders("tools/call") + ("Mcp-Name" to "\u00e9"))
        assertEquals(400, rawName.statusCode())
        assertEquals(HeaderMismatchErrorCode, code(rawName))
        val encodedName = "=?base64?${Base64.getEncoder().encodeToString("\u00e9".toByteArray(Charsets.UTF_8))}?="
        assertEquals(200, post(transport, unicodeCall, modernHeaders("tools/call") + ("Mcp-Name" to encodedName)).statusCode())
        for (headers in listOf(emptyMap(), modernHeaders("ping"), modernHeaders("tools/list") + ("MCP-Protocol-Version" to "2025-11-25"))) {
            val rejected = post(transport, modern("tools/list"), headers)
            assertEquals(400, rejected.statusCode())
            assertEquals(HeaderMismatchErrorCode, code(rejected))
        }
        val unsupported = post(transport, modern("tools/list", version = "2099-01-01"),
            modernHeaders("tools/list") + ("MCP-Protocol-Version" to "2099-01-01"))
        assertEquals(400, unsupported.statusCode())
        assertEquals(UnsupportedProtocolVersionErrorCode, code(unsupported))
        assertEquals("2099-01-01", payload(unsupported)["error"]!!.jsonObject["data"]!!.jsonObject["requested"]!!.jsonPrimitive.content)
        val unknown = post(transport, modern("unknown"), modernHeaders("unknown"))
        assertEquals(404, unknown.statusCode())
        assertEquals(MethodNotFoundErrorCode, code(unknown))
        for (method in listOf("GET", "DELETE")) {
            assertEquals(405, send(transport, method, modernHeaders("tools/list")).statusCode())
        }
    }

    @Test fun `legacy initialization rejects supplied ids and failures do not consume capacity`() =
        server(LiteConfig(httpMaxSessions = 1)) { _, transport ->
            assertEquals(400, post(transport, initialize(), mapOf("Mcp-Session-Id" to "client-chosen")).statusCode())
            assertEquals(400, post(transport, rpc("initialize")).statusCode())
            val first = post(transport, initialize("unknown"))
            assertEquals("2025-11-25", payload(first)["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
            val session = first.headers().firstValue("Mcp-Session-Id").orElseThrow()
            val full = post(transport, initialize())
            assertEquals(429, full.statusCode())
            assertEquals(JsonPrimitive(1), payload(full)["id"])
            assertEquals(400, post(transport, rpc("tools/list"), mapOf("Mcp-Session-Id" to session,
                "MCP-Protocol-Version" to "2025-03-26")).statusCode())
            val beforeReady = post(transport, rpc("tools/list"), mapOf("Mcp-Session-Id" to session))
            assertEquals(InvalidRequestErrorCode, code(beforeReady))
            notifyReady(transport, session)
            assertEquals(200, post(transport, rpc("tools/list"), mapOf("Mcp-Session-Id" to session)).statusCode())
            assertEquals(204, send(transport, "DELETE", mapOf("Mcp-Session-Id" to session)).statusCode())
            assertEquals(404, post(transport, rpc("tools/list"), mapOf("Mcp-Session-Id" to session)).statusCode())
            assertEquals(200, post(transport, initialize()).statusCode())
        }

    @Test fun `host origin authentication and preflight are guarded before routing`() =
        server(LiteConfig(httpBearerToken = "secret", httpAllowedOrigins = setOf("https://client.example"))) { _, transport ->
            val auth = mapOf("Authorization" to "Bearer secret")
            assertEquals(401, post(transport, initialize()).statusCode())
            assertEquals(401, post(transport, initialize(), mapOf("Authorization" to "Bearer wrong")).statusCode())
            assertEquals(403, post(transport, initialize(), auth + ("Origin" to "https://evil.example")).statusCode())
            assertEquals(403, post(transport, initialize(), auth + ("Origin" to "null")).statusCode())
            val accepted = post(transport, initialize(), auth + ("Origin" to "https://client.example"))
            assertEquals(200, accepted.statusCode())
            assertEquals("https://client.example", accepted.headers().firstValue("Access-Control-Allow-Origin").orElseThrow())
            assertEquals(1, accepted.headers().allValues("Access-Control-Allow-Origin").size)
            val preflight = send(transport, "OPTIONS", mapOf("Origin" to "https://client.example",
                "Access-Control-Request-Method" to "POST", "Access-Control-Request-Headers" to "authorization, mcp-method"))
            assertEquals(204, preflight.statusCode())
            assertEquals(403, send(transport, "OPTIONS", mapOf("Origin" to "https://client.example",
                "Access-Control-Request-Method" to "POST", "Access-Control-Request-Headers" to "x-unknown")).statusCode())
            assertTrue(raw(transport, "GET /health HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(raw(transport, "GET /unknown HTTP/1.1\r\nHost: localhost\r\nOrigin: https://evil.example\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 403"))
        }

    @Test fun `body media types accept priorities and byte limits are enforced`() =
        server(LiteConfig(maxHttpRequestBytes = 512)) { _, transport ->
            for (accept in listOf("application/json", "text/event-stream", "*/*;q=1, application/json;q=0")) {
                assertEquals(406, post(transport, initialize(), mapOf("Accept" to accept)).statusCode())
            }
            assertEquals(200, post(transport, initialize(), mapOf("Accept" to "application/*, text/*")).statusCode())
            assertEquals(415, post(transport, initialize(), mapOf("Content-Type" to "text/plain")).statusCode())
            assertEquals(415, post(transport, initialize(), mapOf("Content-Type" to "application/json; charset=iso-8859-1")).statusCode())
            assertEquals(415, post(transport, initialize(), mapOf("Content-Encoding" to "gzip")).statusCode())
            val bytes = "x".repeat(513).toByteArray()
            for (publisher in listOf(HttpRequest.BodyPublishers.ofByteArray(bytes),
                HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) })) {
                assertEquals(413, client.send(builder(transport).POST(publisher).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
            }
            val invalidUtf8 = client.send(builder(transport).POST(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(0xc3.toByte(), 0x28)))
                .build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(400, invalidUtf8.statusCode())
            assertEquals(ParseErrorCode, code(invalidUtf8))
            assertEquals(413, post(transport, "\u00e9".repeat(257)).statusCode())
            for (json in listOf("{", """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"x":NaN}}""")) {
                assertEquals(ParseErrorCode, code(post(transport, json)))
            }
        }

    @Test fun `slow request bodies time out without occupying workers`() =
        server(LiteConfig(httpReadTimeoutMs = 100)) { _, transport ->
            val response = raw(transport, "POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: 100\r\n\r\n{")
            assertTrue(response.startsWith("HTTP/1.1 408"), response)
            assertEquals(200, post(transport, initialize()).statusCode())
        }

    @Test fun `sse emits request progress heartbeat and a final response`() =
        server(LiteConfig(httpSseHeartbeatMs = 25)) { context, transport ->
            context.tools.register(ToolDefinition("slow", "Wait.", JsonObject(emptyMap()))) { Thread.sleep(120); ToolResult.success("done") }
            val called = post(transport, modern("tools/call", buildJsonObject { put("name", "slow") }, progress = "p"),
                modernHeaders("tools/call") + ("Mcp-Name" to "slow"))
            assertEquals("no", called.headers().firstValue("X-Accel-Buffering").orElseThrow())
            assertTrue(called.body().contains(": heartbeat"), called.body())
            val events = events(called.body())
            assertEquals(listOf(0, 1), events.filter { it["method"] == JsonPrimitive("notifications/progress") }
                .map { it["params"]!!.jsonObject["progress"]!!.jsonPrimitive.int })
            assertEquals(JsonPrimitive(1), events.last()["id"])
            assertTrue(events.last().containsKey("result"))
        }

    @Test fun `legacy get stream closes on delete and capacity is released`() =
        server(LiteConfig(httpMaxSseStreams = 1, httpSseHeartbeatMs = 25)) { _, transport ->
            val session = session(transport)
            val response = client.send(HttpRequest.newBuilder(URI.create(transport.endpoint))
                .header("Accept", "text/event-stream").header("Mcp-Session-Id", session).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                assertEquals(200, response.statusCode())
                assertEquals(429, send(transport, "GET", mapOf("Mcp-Session-Id" to session, "Accept" to "text/event-stream")).statusCode())
                assertEquals(204, send(transport, "DELETE", mapOf("Mcp-Session-Id" to session)).statusCode())
                assertTrue(CompletableFuture.supplyAsync { body.reader().readText() }.get(3, TimeUnit.SECONDS).contains("connected"))
            }
            assertEquals(200, post(transport, initialize()).statusCode())
            assertEquals(200, post(transport, modern("tools/call", buildJsonObject { put("name", "unknown") }),
                modernHeaders("tools/call") + ("Mcp-Name" to "unknown")).statusCode())
        }

    @Test fun `busy workers do not start rejected work`() =
        server(LiteConfig(httpMaxConcurrentRequests = 1)) { context, transport ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            context.tools.register(ToolDefinition("wait", "Wait.", JsonObject(emptyMap()))) {
                calls.incrementAndGet(); started.countDown(); release.await(3, TimeUnit.SECONDS); ToolResult.success("done")
            }
            val request = modern("tools/call", buildJsonObject { put("name", "wait") })
            val headers = modernHeaders("tools/call") + ("Mcp-Name" to "wait")
            val first = stream(transport, request, headers)
            first.body().use {
                try {
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    val rejected = post(transport, request, headers)
                    assertEquals(429, rejected.statusCode())
                    assertEquals(1, calls.get())
                } finally { release.countDown() }
                assertTrue(it.reader().readText().contains("result"))
            }
        }

    @Test fun `stream limit rejects tool calls before side effects`() =
        server(LiteConfig(httpMaxSseStreams = 1)) { context, transport ->
            val calls = AtomicInteger()
            context.tools.register(ToolDefinition("write", "Write.", JsonObject(emptyMap()))) { calls.incrementAndGet(); ToolResult.success("done") }
            val session = session(transport)
            val get = client.send(HttpRequest.newBuilder(URI.create(transport.endpoint)).header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", session).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            get.body().use {
                val rejected = post(transport, modern("tools/call", buildJsonObject { put("name", "write") }),
                    modernHeaders("tools/call") + ("Mcp-Name" to "write"))
                assertEquals(429, rejected.statusCode())
                assertEquals(0, calls.get())
                send(transport, "DELETE", mapOf("Mcp-Session-Id" to session))
            }
        }

    @Test fun `modern stream disconnect interrupts only explicitly interruptible tools`() =
        server(LiteConfig(httpSseHeartbeatMs = 25), setOf("read")) { context, transport ->
            val started = CountDownLatch(1)
            val finished = CountDownLatch(1)
            context.tools.register(ToolDefinition("read", "Read.", JsonObject(emptyMap()))) {
                started.countDown()
                try { Thread.sleep(5000); ToolResult.success("done") } finally { finished.countDown() }
            }
            val response = stream(transport, modern("tools/call", buildJsonObject { put("name", "read") }),
                modernHeaders("tools/call") + ("Mcp-Name" to "read"))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            response.body().close()
            assertTrue(finished.await(3, TimeUnit.SECONDS), "Disconnected observational work was not interrupted.")
        }

    @Test fun `write timeout reports uncertainty and still allows work to finish`() =
        server(LiteConfig(httpRequestTimeoutMs = 50, httpSseHeartbeatMs = 25)) { context, transport ->
            val completed = CountDownLatch(1)
            context.tools.register(ToolDefinition("write", "Write.", JsonObject(emptyMap()))) {
                Thread.sleep(150); completed.countDown(); ToolResult.success("done")
            }
            val response = post(transport, modern("tools/call", buildJsonObject { put("name", "write") }),
                modernHeaders("tools/call") + ("Mcp-Name" to "write"))
            assertEquals(InternalErrorCode, code(response))
            assertTrue(response.body().contains("may still complete"))
            assertTrue(completed.await(2, TimeUnit.SECONDS))
        }

    @Test fun `legacy stream disconnect is not a cancellation notification`() =
        server(LiteConfig(httpSseHeartbeatMs = 25), setOf("read")) { context, transport ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            context.tools.register(ToolDefinition("read", "Read.", JsonObject(emptyMap()))) {
                started.countDown()
                try { release.await(3, TimeUnit.SECONDS); ToolResult.success("done") } finally { finished.countDown() }
            }
            val session = session(transport)
            val response = stream(transport, rpc("tools/call", buildJsonObject { put("name", "read") }), mapOf("Mcp-Session-Id" to session))
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS))
                response.body().close()
                assertFalse(finished.await(150, TimeUnit.MILLISECONDS), "Legacy disconnection incorrectly cancelled the request.")
            } finally { response.body().close(); release.countDown() }
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }

    @Test fun `legacy cancellation is scoped to the owning session`() =
        server(LiteConfig(httpSseHeartbeatMs = 25), setOf("read")) { context, transport ->
            val started = CountDownLatch(1)
            val finished = CountDownLatch(1)
            context.tools.register(ToolDefinition("read", "Read.", JsonObject(emptyMap()))) {
                started.countDown()
                try { Thread.sleep(5000); ToolResult.success("done") } finally { finished.countDown() }
            }
            val first = session(transport)
            val second = session(transport)
            val response = stream(transport, rpc("tools/call", buildJsonObject { put("name", "read") }), mapOf("Mcp-Session-Id" to first))
            response.body().use {
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val cancellation = rpc("notifications/cancelled", buildJsonObject { put("requestId", 1) }, notification = true)
                assertEquals(202, post(transport, cancellation, mapOf("Mcp-Session-Id" to second)).statusCode())
                assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
                assertEquals(202, post(transport, cancellation, mapOf("Mcp-Session-Id" to first)).statusCode())
                assertTrue(finished.await(2, TimeUnit.SECONDS))
            }
        }

    @Test fun `remote binding requires opt in and authentication`() {
        LiteContext(LiteConfig(workspacePath = workspace.toString())).use { context ->
            for (config in listOf(context.config.copy(httpHost = "0.0.0.0"),
                context.config.copy(httpHost = "0.0.0.0", allowRemoteHttp = true))) {
                HttpMcpTransport(context, config).use { assertFailsWith<IllegalStateException> { it.start() } }
            }
            HttpMcpTransport(context, context.config.copy(httpHost = "::1", httpPort = 0)).use {
                assertEquals("http://[::1]:0/mcp", it.endpoint)
            }
        }
    }

    private fun server(config: LiteConfig = LiteConfig(), interruptible: Set<String> = emptySet(),
        block: (LiteContext, HttpMcpTransport) -> Unit) {
        LiteContext(config.copy(workspacePath = workspace.toString(), httpPort = 0)).use { context ->
            HttpMcpTransport(context, context.config, interruptible).use { transport -> transport.start(); block(context, transport) }
        }
    }

    private fun builder(transport: HttpMcpTransport, headers: Map<String, String> = emptyMap()): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(transport.endpoint)).timeout(Duration.ofSeconds(8)).also { builder ->
            (mapOf("Content-Type" to "application/json", "Accept" to "application/json, text/event-stream") + headers)
                .forEach { (name, value) -> builder.header(name, value) }
        }
    private fun post(transport: HttpMcpTransport, body: String, headers: Map<String, String> = emptyMap()) =
        client.send(builder(transport, headers).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    private fun send(transport: HttpMcpTransport, method: String, headers: Map<String, String>) =
        client.send(builder(transport, headers).method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    private fun stream(transport: HttpMcpTransport, body: String, headers: Map<String, String>): HttpResponse<InputStream> =
        client.send(builder(transport, headers).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofInputStream())
    private fun raw(transport: HttpMcpTransport, request: String): String = Socket("127.0.0.1", transport.port).use {
        it.soTimeout = 4000
        it.getOutputStream().write(request.toByteArray(Charsets.US_ASCII)); it.getOutputStream().flush()
        it.getInputStream().bufferedReader().readText()
    }
    private fun rpc(method: String, params: JsonObject? = null, notification: Boolean = false): String = buildJsonObject {
        put("jsonrpc", "2.0"); if (!notification) put("id", 1); put("method", method)
        if (params != null) put("params", params)
    }.toString()
    private fun modern(method: String, params: JsonObject = JsonObject(emptyMap()), version: String = MODERN_PROTOCOL_VERSION,
        progress: String? = null) = rpc(method, JsonObject(params + ("_meta" to buildJsonObject {
            put(PROTOCOL_VERSION_META, version); put(CLIENT_CAPABILITIES_META, JsonObject(emptyMap()))
            if (progress != null) put("progressToken", progress)
        })))
    private fun modernHeaders(method: String) = mapOf("MCP-Protocol-Version" to MODERN_PROTOCOL_VERSION, "Mcp-Method" to method)
    private fun initialize(version: String = "2025-11-25") = rpc("initialize", buildJsonObject {
        put("protocolVersion", version); put("capabilities", JsonObject(emptyMap()))
        put("clientInfo", buildJsonObject { put("name", "test"); put("version", "1") })
    })
    private fun session(transport: HttpMcpTransport): String = post(transport, initialize()).headers().firstValue("Mcp-Session-Id")
        .orElseThrow().also { notifyReady(transport, it) }
    private fun notifyReady(transport: HttpMcpTransport, session: String) {
        assertEquals(202, post(transport, rpc("notifications/initialized", notification = true), mapOf("Mcp-Session-Id" to session)).statusCode())
    }
    private fun events(body: String) = body.lines().filter { it.startsWith("data:") }
        .map { Json.parseToJsonElement(it.removePrefix("data:").trim()).jsonObject }
    private fun payload(response: HttpResponse<String>): JsonObject = if (response.body().trimStart().startsWith('{'))
        Json.parseToJsonElement(response.body()).jsonObject else events(response.body()).last { it.containsKey("result") || it.containsKey("error") }
    private fun code(response: HttpResponse<String>) = payload(response)["error"]!!.jsonObject["code"]!!.jsonPrimitive.int
}
