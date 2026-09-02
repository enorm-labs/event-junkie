package de.norm.events.detekt

import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

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

        findings shouldHaveSize 1
        findings.single().message shouldContain "5 lines"
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

        findings.shouldBeEmpty()
    }

    // Otherwise the rule charges a comment for being paragraphed, and the same words written as one
    // wall of text cost less than the readable version of themselves (#741).
    @Test
    fun `a blank separator between paragraphs is not length`() {
        val findings =
            rule.lint(
                """
                /**
                 * One.
                 *
                 * Two.
                 * Three.
                 */
                fun f() = Unit
                """.trimIndent()
            )

        findings shouldHaveSize 1
        findings.single().message shouldContain "5 lines"
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

        findings shouldHaveSize 1
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

        findings.shouldBeEmpty()
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

        findings shouldHaveSize 1
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

        findings.shouldBeEmpty()
    }
}
