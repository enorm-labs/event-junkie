package de.norm.events.detekt

import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

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

        findings shouldHaveSize 1
        findings.single().message shouldContain "80% comment"
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

        findings.shouldBeEmpty()
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

        findings.shouldBeEmpty()
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

        findings.shouldBeEmpty()
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

        findings.shouldBeEmpty()
    }
}
