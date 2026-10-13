package net.evrt.acrab.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcrabContextTest {
    @Test
    fun `context closes exactly once and rejects later use checks`() {
        val context = AcrabContext(AcrabConfig())
        context.close()
        context.close()

        assertFailsWith<IllegalStateException> { context.checkOpen() }
    }

    @Test
    fun `context owns an empty tool registry`() {
        assertTrue(AcrabContext(AcrabConfig()).tools.definitions().isEmpty())
    }
}
