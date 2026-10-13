package net.evrt.acrab.mcp

import kotlinx.serialization.json.*
import net.evrt.acrab.core.*
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class HttpMcpLifecycleTest {
    @TempDir lateinit var workspace: Path

    @Test fun `expired sessions close their streams and release capacity`() {
        val config = AcrabConfig(workspacePath = workspace.toString(), httpMaxSessions = 1, httpSessionIdleTimeoutMs = 100)
        AcrabContext(config).use { context ->
            val closed = mutableListOf<String>()
            val sessions = HttpMcpSessions(config, closed::add)
            val first = sessions.create(McpProtocol(context))!!
            assertNull(sessions.create(McpProtocol(context)))
            first.lastUsed -= TimeUnit.MILLISECONDS.toNanos(101)
            assertNull(sessions.get(first.id))
            assertTrue(first.closed.isCompleted)
            assertEquals(listOf(first.id), closed)
            val second = sessions.create(McpProtocol(context))!!
            assertTrue(second.id != first.id)
            sessions.close()
            assertTrue(second.closed.isCompleted)
        }
    }

    @Test fun `session close never interrupts writes and shutdown waits for completion`() {
        HttpMcpExecution(1).use { execution ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val work = execution.start("session", JsonPrimitive(1), interruptible = false) {
                started.countDown()
                try { release.await(2, TimeUnit.SECONDS); JsonObject(emptyMap()) } finally { finished.countDown() }
            }!!
            assertTrue(started.await(2, TimeUnit.SECONDS))
            execution.closeSession("session")
            assertFalse(work.result.isCancelled)
            assertFalse(work.cancel())
            val closing = Thread { execution.close() }.apply { start() }
            try {
                assertFalse(finished.await(50, TimeUnit.MILLISECONDS))
                assertTrue(closing.isAlive)
            } finally { release.countDown(); closing.join(3000) }
            assertFalse(closing.isAlive)
            assertTrue(work.result.isCompleted)
        }
    }

    @Test fun `stdio modern discovery and unsupported versions preserve ids`() {
        AcrabContext(AcrabConfig(workspacePath = workspace.toString())).use { context ->
            val input = listOf(MODERN_PROTOCOL_VERSION, "2099-01-01").mapIndexed { index, version -> buildJsonObject {
                put("jsonrpc", "2.0"); put("id", index + 1); put("method", "server/discover")
                put("params", buildJsonObject { put("_meta", buildJsonObject {
                    put(PROTOCOL_VERSION_META, version); put(CLIENT_CAPABILITIES_META, JsonObject(emptyMap()))
                }) })
            } }.joinToString("\n", postfix = "\n")
            val output = ByteArrayOutputStream()
            StdioMcpTransport(context, ByteArrayInputStream(input.toByteArray()), output).use { it.serve() }
            val responses = output.toString(Charsets.UTF_8).lines().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }
            assertEquals("complete", responses[0]["result"]!!.jsonObject["resultType"]!!.jsonPrimitive.content)
            assertEquals(JsonPrimitive(2), responses[1]["id"])
            assertEquals(UnsupportedProtocolVersionErrorCode, responses[1]["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        }
    }
}
