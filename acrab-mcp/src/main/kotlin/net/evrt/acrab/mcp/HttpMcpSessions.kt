package net.evrt.acrab.mcp

import kotlinx.coroutines.CompletableDeferred
import net.evrt.acrab.core.AcrabConfig
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class HttpMcpSession(val id: String, val protocol: McpProtocol, var lastUsed: Long) {
    val closed = CompletableDeferred<Unit>()
    val hasGetStream = AtomicBoolean(false)
}

internal class HttpMcpSessions(private val config: AcrabConfig, private val onClosed: (String) -> Unit) {
    private val sessions = mutableMapOf<String, HttpMcpSession>()

    @Synchronized fun create(protocol: McpProtocol): HttpMcpSession? {
        expire()
        if (sessions.size >= config.httpMaxSessions) return null
        val session = HttpMcpSession(UUID.randomUUID().toString(), protocol, System.nanoTime())
        sessions[session.id] = session
        return session
    }

    @Synchronized fun get(id: String): HttpMcpSession? {
        expire()
        return sessions[id]?.also { it.lastUsed = System.nanoTime() }
    }

    @Synchronized fun remove(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        session.closed.complete(Unit)
        onClosed(id)
        return true
    }

    @Synchronized fun expire() {
        val now = System.nanoTime()
        sessions.values.filter { (now - it.lastUsed) / 1_000_000 >= config.httpSessionIdleTimeoutMs }
            .map { it.id }.forEach(::remove)
    }

    @Synchronized fun close() { sessions.keys.toList().forEach(::remove) }
}
