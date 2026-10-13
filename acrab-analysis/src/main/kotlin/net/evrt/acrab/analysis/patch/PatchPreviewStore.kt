package net.evrt.acrab.analysis.patch

import java.nio.file.Path
import java.util.LinkedHashMap

internal data class PreparedPatch(
    val previewId: String,
    val projectId: String,
    val inputSha256: String,
    val canonicalRequestSha256: String,
    val patchedClassSha256: String,
    val patchedClassBytes: ByteArray,
    val verificationScope: String,
    val verificationChecksPassed: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Process-wide, bounded cache shared by patch service instances using the same workspace. */
internal object PatchPreviewStore {
    private data class Key(val workspace: Path, val previewId: String)
    private val lock = Any()
    private val entries = LinkedHashMap<Key, PreparedPatch>(16, 0.75f, true)
    private val serviceReferences = HashMap<Path, Int>()
    private var cachedBytes = 0L

    fun acquire(workspace: Path) = synchronized(lock) {
        val root = workspace.toAbsolutePath().normalize()
        serviceReferences[root] = (serviceReferences[root] ?: 0) + 1
    }

    fun release(workspace: Path) = synchronized(lock) {
        val root = workspace.toAbsolutePath().normalize()
        val remaining = (serviceReferences[root] ?: 1) - 1
        if (remaining > 0) {
            serviceReferences[root] = remaining
        } else {
            serviceReferences.remove(root)
            entries.keys.filter { it.workspace == root }.forEach { key ->
                entries.remove(key)?.let { cachedBytes -= it.patchedClassBytes.size }
            }
        }
    }

    fun put(workspace: Path, patch: PreparedPatch): Boolean = synchronized(lock) {
        removeExpired()
        if (patch.patchedClassBytes.size.toLong() > MAX_BYTES) return@synchronized false
        if (System.currentTimeMillis() - patch.createdAt > TTL_MILLIS) return@synchronized false
        val key = Key(workspace.toAbsolutePath().normalize(), patch.previewId)
        entries[key]?.let { existing ->
            require(
                existing.projectId == patch.projectId &&
                    existing.canonicalRequestSha256 == patch.canonicalRequestSha256 &&
                    existing.patchedClassSha256 == patch.patchedClassSha256,
            ) { "Preview id collision with different prepared bytes." }
            return@synchronized true
        }
        while (entries.size >= MAX_ENTRIES || cachedBytes + patch.patchedClassBytes.size > MAX_BYTES) {
            val eldest = entries.entries.firstOrNull() ?: break
            cachedBytes -= eldest.value.patchedClassBytes.size
            entries.remove(eldest.key)
        }
        val stored = patch.copy(patchedClassBytes = patch.patchedClassBytes.copyOf())
        entries[key] = stored
        cachedBytes += stored.patchedClassBytes.size
        true
    }

    fun get(workspace: Path, previewId: String): PreparedPatch? = synchronized(lock) {
        removeExpired()
        entries[Key(workspace.toAbsolutePath().normalize(), previewId)]
            ?.let { it.copy(patchedClassBytes = it.patchedClassBytes.copyOf()) }
    }

    fun remove(workspace: Path, previewId: String) = synchronized(lock) {
        entries.remove(Key(workspace.toAbsolutePath().normalize(), previewId))
            ?.let { cachedBytes -= it.patchedClassBytes.size }
    }

    private fun removeExpired() {
        val expired = entries.filterValues { System.currentTimeMillis() - it.createdAt > TTL_MILLIS }.keys
        expired.forEach { key -> entries.remove(key)?.let { cachedBytes -= it.patchedClassBytes.size } }
    }

    const val TTL_MILLIS = 15 * 60 * 1000L
    const val MAX_ENTRIES = 64
    const val MAX_BYTES = 128L * 1024 * 1024
}
