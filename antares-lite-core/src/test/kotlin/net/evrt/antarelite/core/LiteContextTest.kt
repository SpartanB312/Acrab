package net.evrt.antarelite.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LiteContextTest {
    @Test
    fun `context closes exactly once and rejects later use checks`() {
        val context = LiteContext(LiteConfig())
        context.close()
        context.close()

        assertFailsWith<IllegalStateException> { context.checkOpen() }
    }

    @Test
    fun `context owns an empty tool registry`() {
        assertTrue(LiteContext(LiteConfig()).tools.definitions().isEmpty())
    }
}
