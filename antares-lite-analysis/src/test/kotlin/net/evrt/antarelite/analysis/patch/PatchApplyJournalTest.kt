package net.evrt.antarelite.analysis.patch

import net.evrt.antarelite.analysis.index.ProjectRepository
import net.evrt.antarelite.core.LiteConfig
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchApplyJournalTest {
    @Test
    fun `recovery registers a published archive and marks its preview consumed`() {
        val workspace = Files.createTempDirectory("patch-journal-recover")
        val config = LiteConfig(workspacePath = workspace.toString())
        val repository = ProjectRepository(config)
        val previewId = "a".repeat(64)
        val output = repository.stagePreparedProject("p-" + "1".repeat(32), "patched.jar", byteArrayOf(1, 2, 3))
        val staged = repository.stagedArchivePath(output.projectId)
        val journal = PatchApplyJournal(workspace)
        val entry = preparedEntry(previewId, output, staged)

        journal.withLock { journal.writePreparedLocked(entry) }
        assertEquals(null, repository.get(output.projectId))
        Files.move(staged, java.nio.file.Path.of(output.archivePath), StandardCopyOption.ATOMIC_MOVE)
        repository.close()

        val reopened = ProjectRepository(config)
        val recoveredJournal = PatchApplyJournal(workspace)
        recoveredJournal.recover(reopened)
        assertEquals(output, reopened.require(output.projectId))
        assertTrue(Files.exists(java.nio.file.Path.of(output.archivePath)))
        assertFalse(Files.exists(staged))
        assertEquals(PatchApplyJournal.CONSUMED, recoveredJournal.withLock {
            recoveredJournal.readLocked(previewId)!!.state
        })

        recoveredJournal.recover(reopened)
        assertEquals(1, reopened.list().size)
        reopened.close()
    }

    @Test
    fun `recovery publishes the staged archive when output move has not happened`() {
        val workspace = Files.createTempDirectory("patch-journal-staged")
        val config = LiteConfig(workspacePath = workspace.toString())
        val repository = ProjectRepository(config)
        val previewId = "b".repeat(64)
        val output = repository.stagePreparedProject("p-" + "2".repeat(32), "patched.jar", byteArrayOf(4, 5, 6))
        val staged = repository.stagedArchivePath(output.projectId)
        val journal = PatchApplyJournal(workspace)
        journal.withLock { journal.writePreparedLocked(preparedEntry(previewId, output, staged)) }
        repository.close()

        val reopened = ProjectRepository(config)
        journal.recover(reopened)
        assertEquals(output, reopened.require(output.projectId))
        assertEquals(byteArrayOf(4, 5, 6).toList(), Files.readAllBytes(java.nio.file.Path.of(output.archivePath)).toList())
        assertEquals(1, reopened.list().size)
        reopened.close()
    }

    @Test
    fun `idempotency key cannot be rebound to another request`() {
        val workspace = Files.createTempDirectory("patch-journal-idempotency")
        val journal = PatchApplyJournal(workspace)
        val keyHash = "c".repeat(64)
        journal.registerPreview("d".repeat(64), keyHash, "first-request")

        val failure = assertFailsWith<IllegalArgumentException> {
            journal.registerPreview("e".repeat(64), keyHash, "different-request")
        }
        assertTrue(failure.message!!.contains("idempotencyKey"))
    }

    private fun preparedEntry(
        previewId: String,
        output: net.evrt.antarelite.analysis.index.ProjectRecord,
        staged: java.nio.file.Path,
    ) = PatchApplyJournalEntry(
        previewId = previewId,
        state = PatchApplyJournal.PREPARED,
        projectId = "p-source",
        inputSha256 = "input-hash",
        canonicalRequestSha256 = "request-hash",
        outputProject = output,
        stagedArchivePath = staged.toString(),
    )
}
