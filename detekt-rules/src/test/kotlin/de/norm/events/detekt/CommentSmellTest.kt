package de.norm.events.detekt

import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class CommentSmellTest {
    private val rule = CommentSmell(TestConfig())

    @Test
    fun `reports a markdown heading in a comment`() {
        val findings =
            rule.lint(
                """
                /**
                 * Summary.
                 *
                 * ## Why this exists
                 */
                fun f() = Unit
                """.trimIndent()
            )

        findings shouldHaveSize 1
        findings.single().message shouldContain "document in the wrong file"
    }

    @Test
    fun `reports a date written as prose`() {
        val findings =
            rule.lint(
                """
                // Measured on staging 2026-08-20, before the retry landed.
                fun f() = Unit
                """.trimIndent()
            )

        findings shouldHaveSize 1
        findings.single().message shouldContain "git blame"
    }

    @Test
    fun `accepts a date inside backticks, which is a format example not a changelog`() {
        val findings =
            rule.lint(
                """
                /** Handles both `"2026-05-16T20:00"` and `"2026-05-16"` inputs. */
                fun f() = Unit
                """.trimIndent()
            )

        findings.shouldBeEmpty()
    }

    @Test
    fun `accepts a date inside a quoted string`() {
        val findings =
            rule.lint(
                """
                // Format example: "astra:2026-06-12-the-adicts".
                fun f() = Unit
                """.trimIndent()
            )

        findings.shouldBeEmpty()
    }

    @Test
    fun `accepts a date inside a fenced block`() {
        val findings =
            rule.lint(
                """
                /**
                 * Summary.
                 *
                 * ```kotlin
                 * parseIsoDate(x)  // 2026-05-16
                 * ```
                 */
                fun f() = Unit
                """.trimIndent()
            )

        findings.shouldBeEmpty()
    }

    @Test
    fun `reports a comment narrating its own history`() {
        val findings =
            rule.lint(
                """
                // The SARIF uploads that used to sit here now run after the build.
                fun f() = Unit
                """.trimIndent()
            )

        findings shouldHaveSize 1
        findings.single().message shouldContain "present tense"
    }

    @Test
    fun `accepts the verb sense of used to, which is what KDoc mostly means`() {
        val findings =
            rule.lint(
                """
                /** @param baseUrl the URL the document was fetched from, used to resolve the links. */
                fun f() = Unit
                """.trimIndent()
            )

        findings.shouldBeEmpty()
    }

    @Test
    fun `reports a TODO`() {
        val findings =
            rule.lint(
                """
                // TODO: handle the empty case.
                fun f() = Unit
                """.trimIndent()
            )

        findings shouldHaveSize 1
        findings.single().message shouldContain "issue"
    }

    @Test
    fun `accepts an ordinary comment`() {
        val findings =
            rule.lint(
                """
                /** Splits a headliner title into its co-billed acts. */
                fun f() = Unit
                """.trimIndent()
            )

        findings.shouldBeEmpty()
    }
}
