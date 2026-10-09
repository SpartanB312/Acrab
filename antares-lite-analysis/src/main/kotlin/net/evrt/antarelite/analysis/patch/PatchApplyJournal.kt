package net.evrt.antarelite.analysis.patch

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.evrt.antarelite.analysis.index.ProjectRecord
import net.evrt.antarelite.analysis.index.ProjectRepository
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
internal data class PatchApplyJournalEntry(
    val previewId: String,
    val state: String,
    val projectId: String,
    val inputSha256: String,
    val canonicalRequestSha256: String,
    val outputProject: ProjectRecord? = null,
    val stagedArchivePath: String? = null,
    val consumedAt: Long? = null,
)

@Serializable
private data class IdempotencyBinding(
    val identitySha256: String,
    val previewId: String,
)

@Serializable
private data class IdempotencyBindings(val entries: Map<String, IdempotencyBinding> = emptyMap())

/** Durable apply state and cross-process workspace lock. */
internal class PatchApplyJournal(private val workspace: Path) {
    private val root = workspace.toAbsolutePath().normalize()
    private val directory = root.resolve("method-patch-journal")
    private val lockFile = root.resolve("method-patch.lock")
    private val idempotencyFile = directory.resolve("idempotency.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun <T> withLock(block: () -> T): T {
        Files.createDirectories(directory)
        val processLock = processLocks.computeIfAbsent(root) { ReentrantLock() }
        return processLock.withLock {
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    fun registerPreview(previewId: String, idempotencyKeyHash: String?, identitySha256: String) = withLock {
        val existing = readLocked(previewId)
        require(existing?.state != CONSUMED) {
            "Preview id has already been consumed: $previewId."
        }
        if (idempotencyKeyHash != null) {
            val bindings = readIdempotencyBindings()
            val previous = bindings.entries[idempotencyKeyHash]
            require(
                previous == null ||
                    (previous.identitySha256 == identitySha256 && previous.previewId == previewId),
            ) {
                "idempotencyKey is already bound to a different source or patch request."
            }
            if (previous == null) {
                writeAtomically(
                    idempotencyFile,
                    json.encodeToString(IdempotencyBindings(bindings.entries + (
                        idempotencyKeyHash to IdempotencyBinding(identitySha256, previewId)
                    ))),
                )
            }
        }
    }

    fun readLocked(previewId: String): PatchApplyJournalEntry? {
        require(previewId.matches(Regex("[a-f0-9]{64}"))) { "Invalid preview id." }
        val file = entryPath(previewId)
        if (!Files.exists(file)) return null
        return json.decodeFromString(Files.readString(file))
    }

    fun writePreparedLocked(entry: PatchApplyJournalEntry) {
        require(entry.state == PREPARED && entry.outputProject != null && entry.stagedArchivePath != null)
        writeEntry(entry)
    }

    fun markConsumedLocked(entry: PatchApplyJournalEntry) {
        require(entry.state == PREPARED && entry.outputProject != null)
        writeEntry(entry.copy(state = CONSUMED, consumedAt = System.currentTimeMillis()))
    }

    fun recover(projects: ProjectRepository) = withLock { recoverLocked(projects) }

    fun recoverLocked(projects: ProjectRepository) {
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") && it != idempotencyFile }
                .forEach { file ->
                    val entry = json.decodeFromString<PatchApplyJournalEntry>(Files.readString(file))
                    if (entry.state == PREPARED) {
                        val output = requireNotNull(entry.outputProject) { "Prepared patch journal is missing output project metadata." }
                        val expectedStage = projects.stagedArchivePath(output.projectId).toAbsolutePath().normalize()
                        require(Path.of(requireNotNull(entry.stagedArchivePath)).toAbsolutePath().normalize() == expectedStage) {
                            "Prepared patch journal references an unexpected staging path."
                        }
                        projects.publishPreparedProject(output)
                        markConsumedLocked(entry)
                    }
                }
        }
    }

    private fun writeEntry(entry: PatchApplyJournalEntry) {
        writeAtomically(entryPath(entry.previewId), json.encodeToString(entry))
    }

    private fun entryPath(previewId: String): Path = directory.resolve("$previewId.json")

    private fun readIdempotencyBindings(): IdempotencyBindings =
        if (Files.exists(idempotencyFile)) json.decodeFromString(Files.readString(idempotencyFile))
        else IdempotencyBindings()

    private fun writeAtomically(path: Path, content: String) {
        val temporary = Files.createTempFile(directory, "${path.fileName}-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        const val PREPARED = "PREPARED"
        const val CONSUMED = "CONSUMED"
        private val processLocks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
