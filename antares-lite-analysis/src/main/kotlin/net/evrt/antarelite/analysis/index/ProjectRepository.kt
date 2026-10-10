package net.evrt.antarelite.analysis.index

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.evrt.antarelite.core.LiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class ProjectRepository(private val config: LiteConfig) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    internal val workspaceRoot = Path.of(config.workspacePath).toAbsolutePath().normalize()
    private val archives = workspaceRoot.resolve("archives")
    private val metadata = workspaceRoot.resolve("projects.ndjson")
    private val projects = ConcurrentHashMap<String, ProjectRecord>()
    private val lock = Any()

    init {
        Files.createDirectories(archives)
        load()
    }

    fun createFromBytes(fileName: String, bytes: ByteArray): ProjectRecord {
        require(fileName.isNotBlank()) { "fileName must not be blank." }
        require(bytes.isNotEmpty()) { "Archive bytes must not be empty." }
        require(bytes.size <= config.maxArchiveBytes) { "Archive exceeds maximum size." }
        val projectId = "p-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val safeName = Path.of(fileName).fileName.toString()
        val target = archives.resolve("$projectId.jar")
        synchronized(lock) {
            Files.write(target, bytes)
            val record = ProjectRecord(
                projectId = projectId,
                fileName = safeName,
                archivePath = target.toString(),
                sha256 = sha256(target),
                createdAt = System.currentTimeMillis(),
            )
            projects[projectId] = record
            append(record)
            return record
        }
    }

    /** Writes a deterministic staging file without making a project visible to readers. */
    fun stagePreparedProject(projectId: String, fileName: String, bytes: ByteArray): ProjectRecord {
        require(projectId.matches(Regex("p-[a-f0-9]{32}"))) { "Invalid prepared project id." }
        require(fileName.isNotBlank()) { "fileName must not be blank." }
        require(bytes.isNotEmpty()) { "Archive bytes must not be empty." }
        require(bytes.size <= config.maxArchiveBytes) { "Archive exceeds maximum size." }
        val safeName = Path.of(fileName).fileName.toString()
        val target = archives.resolve("$projectId.jar")
        val staged = stagedArchivePath(projectId)
        val checksum = sha256(bytes)
        synchronized(lock) {
            projects[projectId]?.let { existing ->
                require(existing.sha256 == checksum) { "Prepared project id already exists with different bytes." }
                return existing
            }
            if (Files.exists(target)) {
                require(sha256(target) == checksum) { "Prepared project output already exists with different bytes." }
            } else if (Files.exists(staged)) {
                require(sha256(staged) == checksum) { "Prepared project staging file has different bytes." }
            } else {
                val temporary = Files.createTempFile(archives, "$projectId-", ".tmp")
                try {
                    java.nio.channels.FileChannel.open(
                        temporary,
                        java.nio.file.StandardOpenOption.WRITE,
                    ).use { channel ->
                        var buffer = java.nio.ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        channel.force(true)
                    }
                    Files.move(temporary, staged, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            return ProjectRecord(
                projectId = projectId,
                fileName = safeName,
                archivePath = target.toString(),
                sha256 = checksum,
                createdAt = System.currentTimeMillis(),
            )
        }
    }

    /** Publishes an already staged project, idempotently completing interrupted registrations. */
    fun publishPreparedProject(record: ProjectRecord): ProjectRecord = synchronized(lock) {
        require(record.projectId.matches(Regex("p-[a-f0-9]{32}"))) { "Invalid prepared project id." }
        val target = archives.resolve("${record.projectId}.jar").normalize()
        require(Path.of(record.archivePath).toAbsolutePath().normalize() == target.toAbsolutePath().normalize()) {
            "Prepared project archive path is outside the project archive store."
        }
        val staged = stagedArchivePath(record.projectId)
        require(staged.normalize().parent == archives.normalize()) { "Invalid prepared project staging path." }

        projects[record.projectId]?.let { existing ->
            require(existing.sha256 == record.sha256) { "Prepared project id already exists with different bytes." }
            require(Files.exists(target) && sha256(target) == record.sha256) {
                "Registered prepared project archive is missing or has changed."
            }
            Files.deleteIfExists(staged)
            return@synchronized existing
        }

        if (Files.exists(target)) {
            require(sha256(target) == record.sha256) { "Prepared project output has different bytes." }
        } else {
            require(Files.exists(staged) && sha256(staged) == record.sha256) {
                "Prepared project staging file is missing or has changed."
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
        }

        rewrite((projects.values + record).distinctBy(ProjectRecord::projectId))
        projects[record.projectId] = record
        Files.deleteIfExists(staged)
        record
    }

    fun stagedArchivePath(projectId: String): Path = archives.resolve("$projectId.jar.pending")

    fun list(): List<ProjectRecord> = projects.values.sortedByDescending { it.createdAt }

    fun get(projectId: String): ProjectRecord? = projects[projectId]

    fun require(projectId: String): ProjectRecord =
        get(projectId) ?: throw IllegalArgumentException("Project not found: $projectId")

    fun verifyIdentity(projectId: String, expectedSha256: String? = null): ArchiveIdentity {
        if (expectedSha256 != null) require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "expectedSha256 must contain 64 hexadecimal characters."
        }
        val project = require(projectId)
        val path = Path.of(project.archivePath)
        val observed = if (Files.isRegularFile(path)) sha256(path) else null
        return ArchiveIdentity(projectId, project.sha256, observed, project.sha256 == observed,
            expectedSha256?.lowercase(), expectedSha256?.let { observed == it.lowercase() })
    }

    fun requireVerified(projectId: String): ProjectRecord {
        val identity = verifyIdentity(projectId)
        require(identity.matches) { "Source archive identity mismatch for $projectId; import changed bytes as a new project." }
        return require(projectId)
    }

    fun delete(projectId: String): Boolean {
        val removed = projects.remove(projectId) ?: return false
        synchronized(lock) {
            val remaining = list().filterNot { it.projectId == projectId }
            rewrite(remaining)
            Files.deleteIfExists(Path.of(removed.archivePath))
        }
        return true
    }

    override fun close() {
        projects.clear()
    }

    private fun load() {
        if (!Files.exists(metadata)) return
        Files.newBufferedReader(metadata).useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                val record = json.decodeFromString(kotlinx.serialization.serializer<ProjectRecord>(), line)
                projects[record.projectId] = record
            }
        }
    }

    private fun append(record: ProjectRecord) {
        Files.writeString(
            metadata,
            json.encodeToString(record) + System.lineSeparator(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    private fun rewrite(records: List<ProjectRecord>) {
        val temporary = metadata.resolveSibling(metadata.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temporary).use { writer ->
            records.forEach { record ->
                writer.write(json.encodeToString(record))
                writer.write(System.lineSeparator())
            }
        }
        Files.move(
            temporary,
            metadata,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun sha256(path: Path): String = java.nio.file.Files.newInputStream(path).use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
