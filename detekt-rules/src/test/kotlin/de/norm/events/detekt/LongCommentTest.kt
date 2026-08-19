package de.norm.events.detekt

import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongCommentTest {
    private val rule = LongComment(TestConfig("maxLines" to 3))

    @Test
    fun `reports a KDoc block longer than the maximum`() {
        val findings =
            rule.lint(
                """
                /**
                 * One.
                 * Two.
                 * Three.
                 */
                fun f() = Unit
                """.trimIndent()
            )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("5 lines"), findings.single().message)
    }

    @Test
    fun `accepts a KDoc block at the maximum`() {
        val findings =
            rule.lint(
                """
                /**
                 * One.
                 */
                fun f() = Unit
                """.trimIndent()
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `treats a run of line comments as one comment`() {
        val findings =
            rule.lint(
                """
                // One.
                // Two.
                // Three.
                // Four.
                fun f() = Unit
                """.trimIndent()
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `a blank line starts a new comment`() {
        val findings =
            rule.lint(
                """
                // One.
                // Two.

                // Three.
                // Four.
                fun f() = Unit
                """.trimIndent()
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `reports a block comment longer than the maximum`() {
        val findings =
            rule.lint(
                """
                /*
                 One.
                 Two.
                 Three.
                */
                fun f() = Unit
                """.trimIndent()
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `honours a suppression on the annotated declaration`() {
        val findings =
            rule.lint(
                """
                /**
                 * One.
                 * Two.
                 * Three.
                 */
                @Suppress("LongComment")
                fun f() = Unit
                """.trimIndent()
            )

        assertEquals(0, findings.size)
    }
}
