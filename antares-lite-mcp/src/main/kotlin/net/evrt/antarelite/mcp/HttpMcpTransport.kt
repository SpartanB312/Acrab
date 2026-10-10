package net.evrt.antarelite.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import net.evrt.antarelite.core.LiteConfig
import net.evrt.antarelite.core.LiteContext
import java.util.concurrent.ConcurrentHashMap

/** MCP Streamable HTTP transport backed by Ktor/Netty. */
class HttpMcpTransport(
    private val context: LiteContext,
    private val config: LiteConfig,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessions = ConcurrentHashMap<String, McpProtocol>()
    private var server: EmbeddedServer<*, *>? = null

    val port: Int
        get() = runBlocking { resolvedPort() }

    val endpoint: String
        get() = "http://${config.httpHost}:$port/mcp"

    fun start() {
        check(server == null) { "HTTP transport is already started." }
        validateBinding()
        val instance = embeddedServer(Netty, host = config.httpHost, port = config.httpPort) {
            routing {
                get("/health") {
                    call.response.header("Cache-Control", "no-store")
                    call.respondText(
                        """
                        {
                          "status": "ready",
                          "server": "${config.serverName}",
                          "version": "${config.serverVersion}",
                          "transport": "http"
                        }
                        """.trimIndent(),
                        ContentType.Application.Json,
                    )
                }

                post("/mcp") {
                    handlePost(call)
                }

                delete("/mcp") {
                    val id = call.request.headers["Mcp-Session-Id"]
                    if (id.isNullOrBlank() || sessions.remove(id) == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }.start(wait = false)
        server = instance
    }

    override fun close() {
        server?.stop(1_000, 2_000)
        server = null
        sessions.clear()
    }

    private suspend fun resolvedPort(): Int {
        val instance = server ?: return config.httpPort
        return instance.engine.resolvedConnectors().first().port
    }

    private fun validateBinding() {
        val remote = config.httpHost !in LOCAL_HOSTS
        check(config.allowRemoteHttp || !remote) {
            "Non-loopback HTTP binding requires allowRemoteHttp=true."
        }
    }

    private suspend fun handlePost(call: ApplicationCall) {
        val text = call.receiveText()
        if (text.toByteArray(Charsets.UTF_8).size > config.maxHttpRequestBytes) {
            call.respondJson(
                HttpStatusCode.PayloadTooLarge,
                JsonRpc.errorResponse(JsonNull, InvalidRequestErrorCode, "Request body exceeds limit."),
            )
            return
        }

        val request = try {
            JsonRpc.parse(text)
        } catch (failure: JsonRpcException) {
            call.respondJson(HttpStatusCode.OK, JsonRpc.errorResponse(JsonNull, failure.code, failure.message ?: "Invalid request."))
            return
        } catch (failure: Exception) {
            call.respondJson(HttpStatusCode.OK, JsonRpc.errorResponse(JsonNull, ParseErrorCode, "Parse error"))
            return
        }

        val requestedId = call.request.headers["Mcp-Session-Id"]
        if (requestedId != null && requestedId.length > 128) {
            call.respondJson(
                HttpStatusCode.BadRequest,
                JsonRpc.errorResponse(request.id, InvalidRequestErrorCode, "MCP session id is too long."),
            )
            return
        }
        val sessionId = requestedId ?: if (request.method == "initialize") java.util.UUID.randomUUID().toString() else null
        if (sessionId == null) {
            call.respondJson(HttpStatusCode.OK, JsonRpc.errorResponse(request.id, InvalidRequestErrorCode,
                "Initialize a session and retain its Mcp-Session-Id header."))
            return
        }
        val protocol = if (request.method == "initialize") sessions.computeIfAbsent(sessionId) { McpProtocol(context) }
            else sessions[sessionId]
        if (protocol == null) {
            call.respondJson(HttpStatusCode.NotFound, JsonRpc.errorResponse(request.id, InvalidRequestErrorCode,
                "Unknown or closed MCP session; initialize a new session."))
            return
        }

        try {
            val result = protocol.dispatch(request)
            call.response.header("Mcp-Session-Id", sessionId)
            if (request.isNotification) {
                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respondJson(HttpStatusCode.OK, protocol.successResponse(request.id ?: JsonNull, result))
            }
        } catch (failure: JsonRpcException) {
            if (request.isNotification) call.respond(HttpStatusCode.Accepted)
            else call.respondJson(HttpStatusCode.OK, protocol.errorResponse(request.id, failure.code, failure.message ?: "Invalid request."))
        } catch (failure: Exception) {
            if (request.isNotification) call.respond(HttpStatusCode.Accepted)
            else call.respondJson(HttpStatusCode.OK, protocol.errorResponse(request.id, InternalErrorCode, failure.message ?: "Internal error."))
        }
    }

    private suspend fun ApplicationCall.respondJson(
        status: HttpStatusCode,
        response: JsonObject,
    ) {
        respondText(
            json.encodeToString(JsonObject.serializer(), response),
            ContentType.Application.Json,
            status,
        )
    }

    private companion object {
        val LOCAL_HOSTS = setOf("127.0.0.1", "::1", "localhost")
    }
}

