package net.evrt.antarelite.mcp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.SSE
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.ServerSSESession
import io.ktor.utils.io.readAvailable
import io.ktor.util.AttributeKey
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*
import net.evrt.antarelite.core.LiteConfig
import net.evrt.antarelite.core.LiteContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Version-aware Streamable HTTP, with JSON and request-scoped SSE responses. */
class HttpMcpTransport(
    private val context: LiteContext,
    private val config: LiteConfig,
    private val interruptibleTools: Set<String> = emptySet(),
) : AutoCloseable {
    private val security = HttpMcpSecurity(config)
    private val execution = HttpMcpExecution(config.httpMaxConcurrentRequests)
    private val sessions = HttpMcpSessions(config, execution::closeSession)
    private val requestSlots = Semaphore(config.httpMaxConcurrentRequests + 2)
    private val streams = Semaphore(config.httpMaxSseStreams)
    private var maintenance: CoroutineScope? = null
    private var server: EmbeddedServer<*, *>? = null
    private var closed = false

    val port: Int get() = runBlocking { server?.engine?.resolvedConnectors()?.first()?.port ?: config.httpPort }
    val endpoint: String get() {
        val host = if (':' in config.httpHost && !config.httpHost.startsWith('[')) "[${config.httpHost}]" else config.httpHost
        return "http://$host:$port/mcp"
    }

    @Synchronized fun start() {
        check(server == null && !closed) { "HTTP transport is already started or closed." }
        val remote = config.httpHost !in setOf("127.0.0.1", "::1", "localhost")
        check(!remote || config.allowRemoteHttp) { "Non-loopback HTTP binding requires allowRemoteHttp=true." }
        check(!remote || config.httpBearerToken != null) { "Remote HTTP requires a bearer token and an authenticated TLS boundary." }
        server = embeddedServer(Netty, host = config.httpHost, port = config.httpPort) {
            install(SSE)
            intercept(ApplicationCallPipeline.Plugins) {
                (call as? NettyApplicationCall)?.let { call.attributes.put(ENGINE_CALL, it) }
                val preflight = call.request.httpMethod == HttpMethod.Options && call.request.path() == "/mcp"
                if (!guard(call, authenticate = !preflight)) finish()
            }
            routing {
                get("/health") {
                    call.respondJson(HttpStatusCode.OK, buildJsonObject {
                        put("status", "ready"); put("server", config.serverName)
                        put("version", config.serverVersion); put("transport", "http")
                    })
                }
                post("/mcp") {
                    if (!requestSlots.tryAcquire()) busy(call)
                    else try { handlePost(call) } finally { requestSlots.release() }
                }
                get("/mcp") { handleGet(call) }
                delete("/mcp") {
                    if (call.request.headers["MCP-Protocol-Version"] == MODERN_PROTOCOL_VERSION) methodNotAllowed(call)
                    else {
                        val session = requireSession(call) ?: return@delete
                        if (!validateLegacyVersion(call, session)) return@delete
                        sessions.remove(session.id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                options("/mcp") {
                    val origin = call.request.headers[HttpHeaders.Origin]
                    val method = call.request.headers[HttpHeaders.AccessControlRequestMethod]
                    val headers = call.request.headers[HttpHeaders.AccessControlRequestHeaders].orEmpty()
                        .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    val allowed = setOf("content-type", "authorization", "mcp-session-id", "mcp-protocol-version",
                        "mcp-method", "mcp-name", "last-event-id")
                    if (origin == null || method !in setOf("POST", "GET", "DELETE") || headers.any { it !in allowed }) {
                        call.respond(HttpStatusCode.Forbidden)
                    } else {
                        call.response.header(HttpHeaders.AccessControlAllowMethods, "POST, GET, DELETE")
                        call.response.header(HttpHeaders.AccessControlAllowHeaders, allowed.joinToString(", "))
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }.start(wait = false)
        maintenance = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope ->
            scope.launch {
                while (isActive) {
                    delay(minOf(1000L, maxOf(1L, config.httpSessionIdleTimeoutMs / 2)))
                    sessions.expire()
                }
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        maintenance?.cancel()
        sessions.close()
        server?.stop(1_000, 2_000)
        server = null
        execution.close()
    }

    private suspend fun guard(call: ApplicationCall, authenticate: Boolean = true): Boolean {
        val failure = security.validate(call, authenticate) ?: return true
        call.response.header(HttpHeaders.Connection, "close")
        call.respond(failure)
        call.closeAfterResponse()
        return false
    }

    private suspend fun handlePost(call: ApplicationCall) {
        val mediaType = runCatching { call.request.contentType() }.getOrNull()
        if (mediaType == null || !mediaType.match(ContentType.Application.Json) ||
            mediaType.parameter("charset")?.lowercase()?.let { it !in setOf("utf-8", "utf8") } == true ||
            call.request.headers[HttpHeaders.ContentEncoding]?.let { !it.equals("identity", true) } == true) {
            call.response.header(HttpHeaders.Connection, "close")
            call.respond(HttpStatusCode.UnsupportedMediaType)
            call.closeAfterResponse()
            return
        }
        if (!accepts(call, ContentType.Application.Json) || !accepts(call, ContentType.Text.EventStream)) {
            call.response.header(HttpHeaders.Connection, "close")
            call.respond(HttpStatusCode.NotAcceptable)
            call.closeAfterResponse()
            return
        }
        val request = try {
            JsonRpc.parse(receiveBounded(call))
        } catch (failure: BodyTooLarge) {
            call.response.header(HttpHeaders.Connection, "close")
            call.respondError(HttpStatusCode.PayloadTooLarge, null, InvalidRequestErrorCode, "Request body exceeds limit.")
            call.closeAfterResponse()
            return
        } catch (_: TimeoutCancellationException) {
            call.response.header(HttpHeaders.Connection, "close")
            call.respondError(HttpStatusCode.RequestTimeout, null, InvalidRequestErrorCode, "Request body read timed out.")
            call.closeAfterResponse()
            return
        } catch (failure: JsonRpcException) {
            call.respondError(HttpStatusCode.BadRequest, failure.requestId, failure.code, failure.message ?: "Invalid request.",
                failure.data)
            return
        } catch (_: java.nio.charset.CharacterCodingException) {
            call.respondError(HttpStatusCode.BadRequest, null, ParseErrorCode, "Request must be valid UTF-8.")
            return
        }
        val headerVersion = call.request.headers["MCP-Protocol-Version"]
        val modern = McpProtocol.hasModernMetadata(request) || headerVersion == MODERN_PROTOCOL_VERSION
        if (modern) {
            try {
                val version = McpProtocol.validateModernMetadata(request)
                validateHeader(call, "MCP-Protocol-Version", version)
                validateHeader(call, "Mcp-Method", request.method)
                if (request.method == "tools/call") {
                    val name = (request.params?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw JsonRpcException(InvalidParamsErrorCode, "name must be a string.")
                    validateHeader(call, "Mcp-Name", name, encoded = true)
                }
            } catch (failure: JsonRpcException) {
                call.respondError(HttpStatusCode.BadRequest, request.id, failure.code, failure.message ?: "Invalid request.",
                    failure.data, request.isNotification)
                return
            }
        } else if (headerVersion != null && headerVersion !in LEGACY_PROTOCOL_VERSIONS) {
            call.respondError(HttpStatusCode.BadRequest, request.id, InvalidRequestErrorCode, "Unsupported MCP-Protocol-Version.")
            return
        }

        if (!modern && request.method == "initialize") {
            if (call.request.headers["Mcp-Session-Id"] != null || request.isNotification) {
                call.respondError(HttpStatusCode.BadRequest, request.id, InvalidRequestErrorCode,
                    "Initialize without a session header and with a request id.", omitId = request.isNotification)
                return
            }
            val protocol = McpProtocol(context)
            val result = try { protocol.dispatch(request) } catch (failure: JsonRpcException) {
                call.respondError(HttpStatusCode.BadRequest, request.id, failure.code, failure.message ?: "Invalid initialization.")
                return
            }
            val session = sessions.create(protocol) ?: run { busy(call, request.id); return }
            call.response.header("Mcp-Session-Id", session.id)
            call.response.header("MCP-Protocol-Version", protocol.negotiatedVersion!!)
            call.respondJson(HttpStatusCode.OK, protocol.successResponse(request.id!!, result))
            return
        }
        val session = if (modern) null else requireSession(call, request.id, request.isNotification) ?: return
        if (session != null && !validateLegacyVersion(call, session, request.id, request.isNotification)) return
        if (session != null) call.response.header("Mcp-Session-Id", session.id)
        val protocol = session?.protocol ?: McpProtocol(context)

        if (request.isNotification) {
            try {
                if (!modern && request.method == "notifications/cancelled") {
                    val id = request.params?.get("requestId")
                        ?: throw JsonRpcException(InvalidParamsErrorCode, "Missing requestId.")
                    JsonRpc.requireValidId(id)
                    execution.cancel(session!!.id, id)
                } else protocol.dispatch(request, modern)
                call.respond(HttpStatusCode.Accepted)
            } catch (failure: JsonRpcException) {
                call.respondError(HttpStatusCode.BadRequest, null, failure.code, failure.message ?: "Invalid notification.", omitId = true)
            }
            return
        }
        if (modern && request.method !in setOf("server/discover", "tools/list", "tools/call")) {
            call.respondError(HttpStatusCode.NotFound, request.id, MethodNotFoundErrorCode, "Method not found: ${request.method}")
            return
        }
        val progressToken = (request.params?.get("_meta") as? JsonObject)?.get("progressToken")
        if (progressToken != null) try { JsonRpc.requireValidId(progressToken) } catch (_: JsonRpcException) {
            call.respondError(HttpStatusCode.BadRequest, request.id, InvalidParamsErrorCode, "Invalid progressToken.")
            return
        }
        val toolName = (request.params?.get("name") as? JsonPrimitive)?.content
        val useStream = request.method == "tools/call"
        if (useStream && !streams.tryAcquire()) { busy(call, request.id); return }
        val work = execution.start(session?.id, request.id!!, toolName in interruptibleTools) {
            try { protocol.successResponse(request.id, protocol.dispatch(request, modern)) }
            catch (failure: JsonRpcException) { JsonRpc.errorResponse(request.id, failure.code, failure.message ?: "Invalid request.", failure.data) }
            catch (_: InterruptedException) { throw CancellationException("Request cancelled.") }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { JsonRpc.errorResponse(request.id, InternalErrorCode, "Internal tool failure.") }
        } ?: run { if (useStream) streams.release(); busy(call, request.id); return }

        if (useStream) {
            val started = AtomicBoolean(false)
            call.response.header(HttpHeaders.CacheControl, "no-cache, no-transform")
            call.response.header("X-Accel-Buffering", "no")
            try {
                call.respond(SSEServerContent(call) {
                    started.set(true)
                    try {
                        send(comments = "connected")
                        if (progressToken != null) sendProgress(progressToken, 0)
                        val response = withTimeout(config.httpRequestTimeoutMs) {
                            waitForResult(work, session)
                        }
                        if (progressToken != null) sendProgress(progressToken, 1)
                        send(data = Json.encodeToString(JsonObject.serializer(), response), event = "message")
                    } catch (_: TimeoutCancellationException) {
                        send(data = JsonRpc.errorResponse(request.id, InternalErrorCode, TIMEOUT_MESSAGE).toString(), event = "message")
                    } finally {
                        if (!work.result.isCompleted) work.cancel()
                        streams.release()
                    }
                })
            } finally { if (!started.get()) { work.cancel(); streams.release() } }
        } else {
            try { call.respondJson(HttpStatusCode.OK, withTimeout(config.httpRequestTimeoutMs) { work.result.await() }) }
            catch (_: TimeoutCancellationException) {
                work.cancel()
                call.respondError(HttpStatusCode.GatewayTimeout, request.id, InternalErrorCode, TIMEOUT_MESSAGE)
            } finally { if (!work.result.isCompleted) work.cancel() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun ServerSSESession.waitForResult(work: HttpMcpWork, session: HttpMcpSession?): JsonObject {
        while (true) {
            val result = select<JsonObject?> {
                work.result.onAwait { it }
                session?.closed?.onAwait { throw CancellationException("Session closed.") }
                onTimeout(config.httpSseHeartbeatMs) { send(comments = "heartbeat"); null }
            }
            if (result != null) return result
        }
    }

    private suspend fun ServerSSESession.sendProgress(token: JsonElement, progress: Int) {
        send(data = buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "notifications/progress")
            put("params", buildJsonObject { put("progressToken", token); put("progress", progress); put("total", 1) })
        }.toString(), event = "message")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun handleGet(call: ApplicationCall) {
        if (call.request.headers["MCP-Protocol-Version"] == MODERN_PROTOCOL_VERSION ||
            call.request.headers["Mcp-Session-Id"] == null) { methodNotAllowed(call); return }
        if (!accepts(call, ContentType.Text.EventStream)) { call.respond(HttpStatusCode.NotAcceptable); return }
        val session = requireSession(call) ?: return
        if (!validateLegacyVersion(call, session)) return
        if (!session.hasGetStream.compareAndSet(false, true)) { busy(call); return }
        if (!streams.tryAcquire()) { session.hasGetStream.set(false); busy(call); return }
        val started = AtomicBoolean(false)
        call.response.header(HttpHeaders.CacheControl, "no-cache, no-transform")
        call.response.header("X-Accel-Buffering", "no")
        call.response.header("Mcp-Session-Id", session.id)
        try {
            call.respond(SSEServerContent(call) {
                started.set(true)
                try {
                    send(comments = "connected")
                    while (!session.closed.isCompleted) select<Unit> {
                        session.closed.onAwait { }
                        onTimeout(config.httpSseHeartbeatMs) { send(comments = "heartbeat") }
                    }
                } finally { session.hasGetStream.set(false); streams.release() }
            })
        } finally { if (!started.get()) { session.hasGetStream.set(false); streams.release() } }
    }

    private suspend fun requireSession(call: ApplicationCall, id: JsonElement? = null, omitId: Boolean = false): HttpMcpSession? {
        val header = call.request.headers.getAll("Mcp-Session-Id")?.singleOrNull()
        if (header == null || header.isEmpty() || header.length > 128 || header.any { it.code !in 0x21..0x7e }) {
            call.respondError(HttpStatusCode.BadRequest, id, InvalidRequestErrorCode, "A valid Mcp-Session-Id is required.", omitId = omitId)
            return null
        }
        return sessions.get(header) ?: run {
            call.respondError(HttpStatusCode.NotFound, id, InvalidRequestErrorCode, "Unknown or expired MCP session.", omitId = omitId)
            null
        }
    }

    private suspend fun validateLegacyVersion(call: ApplicationCall, session: HttpMcpSession,
        id: JsonElement? = null, omitId: Boolean = false): Boolean {
        val values = call.request.headers.getAll("MCP-Protocol-Version")
        if (values != null && (values.size != 1 || values.single() != session.protocol.negotiatedVersion)) {
            call.respondError(HttpStatusCode.BadRequest, id, InvalidRequestErrorCode, "Protocol header differs from the negotiated version.", omitId = omitId)
            return false
        }
        return true
    }

    private fun validateHeader(call: ApplicationCall, name: String, expected: String, encoded: Boolean = false) {
        val value = call.request.headers.getAll(name)?.singleOrNull()
        val decoded = if (encoded && value?.startsWith("=?base64?") == true && value.endsWith("?=")) {
            runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getDecoder().decode(value.substring(9, value.length - 2)))).toString()
            }.getOrNull()
        } else value
        if (decoded != expected) throw JsonRpcException(HeaderMismatchErrorCode, "$name is missing or does not match the request body.")
    }

    private fun accepts(call: ApplicationCall, type: ContentType): Boolean {
        val header = call.request.headers.getAll(HttpHeaders.Accept)?.joinToString(",") ?: return false
        return runCatching {
            val matching = parseHeaderValue(header).map { it to ContentType.parse(it.value) }
                .filter { (_, range) -> type.match(range) }
            val specificity = matching.maxOfOrNull { (_, range) ->
                (if (range.contentType == "*") 0 else 1) + (if (range.contentSubtype == "*") 0 else 1)
            } ?: return false
            matching.filter { (_, range) ->
                (if (range.contentType == "*") 0 else 1) + (if (range.contentSubtype == "*") 0 else 1) == specificity
            }.any { (range, _) -> range.quality > 0 }
        }.getOrDefault(false)
    }

    private class BodyTooLarge : Exception()
    private suspend fun receiveBounded(call: ApplicationCall): String {
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length > config.maxHttpRequestBytes) throw BodyTooLarge()
        val input = call.receiveChannel()
        val bytes = ByteArrayOutputStream()
        try {
            withTimeout(config.httpReadTimeoutMs) {
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    total += read
                    if (total > config.maxHttpRequestBytes) throw BodyTooLarge()
                    bytes.write(buffer, 0, read)
                }
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (failure: Exception) { input.cancel(failure); throw failure }
    }

    private suspend fun busy(call: ApplicationCall, id: JsonElement? = null) {
        call.response.header(HttpHeaders.RetryAfter, "1")
        call.response.header(HttpHeaders.Connection, "close")
        call.respondError(HttpStatusCode.TooManyRequests, id, InternalErrorCode, "HTTP capacity is exhausted; no new work was started.")
        call.closeAfterResponse()
    }
    private suspend fun methodNotAllowed(call: ApplicationCall) {
        call.response.header(HttpHeaders.Allow, "POST")
        call.respond(HttpStatusCode.MethodNotAllowed)
    }
    private suspend fun ApplicationCall.respondError(status: HttpStatusCode, id: JsonElement?, code: Int,
        message: String, data: JsonObject? = null, omitId: Boolean = false) {
        respondJson(status, JsonRpc.errorResponse(id, code, message, data, omitId))
    }
    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, response: JsonObject) {
        this.response.header(HttpHeaders.CacheControl, "no-store")
        respondText(Json.encodeToString(JsonObject.serializer(), response), ContentType.Application.Json, status)
    }
    private suspend fun ApplicationCall.closeAfterResponse() {
        // Netty does not close a keep-alive socket merely because a response says Connection: close.
        attributes.getOrNull(ENGINE_CALL)?.let { engine ->
            engine.responseWriteJob.join()
            engine.context.close()
        }
    }
    private companion object {
        val ENGINE_CALL = AttributeKey<NettyApplicationCall>("antares-http-engine-call")
        const val TIMEOUT_MESSAGE = "Request timed out. Noninterruptible work may still complete; do not blindly retry writes."
    }
}

