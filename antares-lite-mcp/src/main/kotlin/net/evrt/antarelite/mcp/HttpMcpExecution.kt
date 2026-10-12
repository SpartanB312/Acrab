package net.evrt.antarelite.mcp

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

internal class HttpMcpWork(val result: CompletableDeferred<kotlinx.serialization.json.JsonObject>,
    private val future: Future<*>, private val interruptible: Boolean) {
    fun cancel(): Boolean {
        if (!interruptible) return false
        result.cancel()
        future.cancel(true)
        return true
    }
}

internal class HttpMcpExecution(limit: Int) : AutoCloseable {
    private val sequence = AtomicInteger()
    private val executor = ThreadPoolExecutor(limit, limit, 0L, TimeUnit.MILLISECONDS, SynchronousQueue(),
        ThreadFactory { runnable -> Thread(runnable, "antares-http-worker-${sequence.incrementAndGet()}").apply { isDaemon = true } })
    private data class Key(val session: String, val id: kotlinx.serialization.json.JsonElement)
    private val active = ConcurrentHashMap<Key, HttpMcpWork>()

    fun start(session: String?, id: kotlinx.serialization.json.JsonElement, interruptible: Boolean,
        block: () -> kotlinx.serialization.json.JsonObject): HttpMcpWork? {
        val key = Key(session ?: java.util.UUID.randomUUID().toString(), id)
        val result = CompletableDeferred<kotlinx.serialization.json.JsonObject>()
        lateinit var work: HttpMcpWork
        val future = object : FutureTask<Unit>({
            try { result.complete(block()) } catch (failure: Throwable) { result.completeExceptionally(failure) }
        }, Unit) {
            override fun done() { active.remove(key, work) }
        }
        work = HttpMcpWork(result, future, interruptible)
        if (active.putIfAbsent(key, work) != null) return null
        try { executor.execute(future) } catch (_: RejectedExecutionException) {
            active.remove(key, work)
            return null
        }
        return work
    }

    fun cancel(session: String, id: kotlinx.serialization.json.JsonElement) { active[Key(session, id)]?.cancel() }
    fun closeSession(session: String) { active.entries.filter { it.key.session == session }.forEach { it.value.cancel() } }

    override fun close() {
        active.values.forEach { it.cancel() }
        executor.shutdown()
        // Do not dispose the shared project context while a noninterruptible publication is running.
        while (!executor.awaitTermination(1, TimeUnit.SECONDS)) { }
    }
}
