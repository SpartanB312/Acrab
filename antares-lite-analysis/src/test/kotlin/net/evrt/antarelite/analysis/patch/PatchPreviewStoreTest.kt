package net.evrt.antarelite.analysis.patch

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatchPreviewStoreTest {
    @Test
    fun `cached class bytes are defensive and scoped to the workspace`() {
        val workspace = Files.createTempDirectory("patch-preview-store")
        val otherWorkspace = Files.createTempDirectory("patch-preview-other")
        val previewId = "a".repeat(64)
        val original = byteArrayOf(1, 2, 3)
        PatchPreviewStore.acquire(workspace)
        try {
            assertTrue(PatchPreviewStore.put(workspace, preparedPatch(previewId, original)))
            val returned = PatchPreviewStore.get(workspace, previewId)!!
            returned.patchedClassBytes[0] = 99
            assertContentEquals(original, PatchPreviewStore.get(workspace, previewId)!!.patchedClassBytes)
            assertNull(PatchPreviewStore.get(otherWorkspace, previewId))
        } finally {
            PatchPreviewStore.release(workspace)
        }
        assertNull(PatchPreviewStore.get(workspace, previewId))
    }

    @Test
    fun `expired previews are not stored`() {
        val workspace = Files.createTempDirectory("patch-preview-expired")
        PatchPreviewStore.acquire(workspace)
        try {
            val expired = preparedPatch(
                "b".repeat(64),
                byteArrayOf(1),
                System.currentTimeMillis() - PatchPreviewStore.TTL_MILLIS - 1,
            )
            assertFalse(PatchPreviewStore.put(workspace, expired))
            assertNull(PatchPreviewStore.get(workspace, expired.previewId))
        } finally {
            PatchPreviewStore.release(workspace)
        }
    }

    @Test
    fun `cache evicts the least recently used preview at its entry limit`() {
        val workspace = Files.createTempDirectory("patch-preview-cap")
        PatchPreviewStore.acquire(workspace)
        try {
            repeat(PatchPreviewStore.MAX_ENTRIES + 1) { index ->
                val id = index.toString(16).padStart(64, '0')
                assertTrue(PatchPreviewStore.put(workspace, preparedPatch(id, byteArrayOf(index.toByte()))))
            }
            assertNull(PatchPreviewStore.get(workspace, "0".repeat(64)))
            val mostRecentId = PatchPreviewStore.MAX_ENTRIES.toString(16).padStart(64, '0')
            assertEquals(
                PatchPreviewStore.MAX_ENTRIES,
                PatchPreviewStore.get(workspace, mostRecentId)?.patchedClassBytes?.single()?.toInt(),
            )
        } finally {
            PatchPreviewStore.release(workspace)
        }
    }

    private fun preparedPatch(id: String, bytes: ByteArray, createdAt: Long = System.currentTimeMillis()) =
        PreparedPatch(
            previewId = id,
            projectId = "p-test",
            inputSha256 = "input",
            canonicalRequestSha256 = "request",
            patchedClassSha256 = "class",
            patchedClassBytes = bytes,
            verificationScope = "asm-static",
            verificationChecksPassed = listOf("structure"),
            createdAt = createdAt,
        )

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue(expected.contentEquals(actual))
    }
}
