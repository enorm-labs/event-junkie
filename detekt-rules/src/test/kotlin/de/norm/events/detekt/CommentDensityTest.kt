package de.norm.events.detekt

import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommentDensityTest {
    private val rule = CommentDensity(TestConfig("maxPercent" to 50, "minCommentLines" to 3))

    @Test
    fun `reports a file that is more comment than the maximum allows`() {
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
        assertTrue(findings.single().message.contains("80% comment"), findings.single().message)
    }

    @Test
    fun `accepts a file at the maximum`() {
        val findings =
            rule.lint(
                """
                // One.
                // Two.
                // Three.
                fun a() = Unit
                fun b() = Unit
                fun c() = Unit
                """.trimIndent()
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `ignores a file with too few comment lines to be meaningful`() {
        val findings =
            rule.lint(
                """
                // One.
                fun f() = Unit
                """.trimIndent()
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `counts a trailing comment as code, the way cloc does`() {
        val findings =
            rule.lint(
                """
                // One.
                // Two.
                // Three.
                fun a() = Unit // trailing
                fun b() = Unit // trailing
                fun c() = Unit // trailing
                """.trimIndent()
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `blank lines do not count towards the total`() {
        val findings =
            rule.lint(
                """
                // One.
                // Two.
                // Three.

                fun a() = Unit

                fun b() = Unit

                fun c() = Unit
                """.trimIndent()
            )

        assertEquals(0, findings.size)
    }
}
