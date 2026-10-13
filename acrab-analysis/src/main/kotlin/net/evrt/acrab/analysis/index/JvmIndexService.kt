package net.evrt.acrab.analysis.index

import java.util.concurrent.ConcurrentHashMap

class JvmIndexService(
    private val projects: ProjectRepository,
    private val indexer: JarIndexer = JarIndexer(),
) : AutoCloseable {
    private val cache = ConcurrentHashMap<String, ProjectIndex>()

    fun ensureIndex(projectId: String): ProjectIndex {
        val project = try { projects.requireVerified(projectId) } catch (failure: IllegalArgumentException) {
            cache.remove(projectId)
            throw failure
        }
        require(project.inputType == "jar") { "JVM indexing expects a JAR project; convert Android bytecode and import the derived JAR first." }
        cache[projectId]?.let { return it }
        val index = indexer.index(project.projectId, java.nio.file.Path.of(project.archivePath), project.sha256)
        cache[projectId] = index
        return index
    }

    fun invalidate(projectId: String) {
        cache.remove(projectId)
    }

    override fun close() {
        cache.clear()
    }

    fun listClasses(projectId: String, query: String?, limit: Int): List<ClassInfo> =
        filter(ensureIndex(projectId).classes, query, limit) { it.name }

    fun classSummary(projectId: String, className: String): ClassInfo =
        ensureIndex(projectId).classes.firstOrNull { it.name == className }
            ?: throw IllegalArgumentException("Class not found in project $projectId: $className")

    fun searchStrings(projectId: String, query: String?, limit: Int): List<StringReference> =
        filter(ensureIndex(projectId).strings, query, limit) { it.value }

    fun archiveSummary(projectId: String): JarSummary = ensureIndex(projectId).summary

    /** Archive backing a project, for detectors that need the instruction stream. */
    fun archivePathFor(projectId: String): java.nio.file.Path? =
        runCatching { java.nio.file.Path.of(projects.require(projectId).archivePath) }.getOrNull()

    private inline fun <T> filter(
        values: List<T>,
        query: String?,
        rawLimit: Int,
        crossinline text: (T) -> String,
    ): List<T> {
        val normalized = query?.trim()?.lowercase().orEmpty()
        return values.asSequence()
            .filter { normalized.isBlank() || text(it).lowercase().contains(normalized) }
            .take(rawLimit.coerceIn(1, MAX_QUERY_LIMIT))
            .toList()
    }

    companion object {
        const val MAX_QUERY_LIMIT = 1000
    }
}
