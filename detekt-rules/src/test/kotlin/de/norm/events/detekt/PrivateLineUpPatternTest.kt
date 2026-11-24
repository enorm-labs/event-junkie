package de.norm.events.detekt

import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import kotlin.test.Test

class PrivateLineUpPatternTest {
    private val rule = PrivateLineUpPattern(TestConfig())

    private fun findings(
        literal: String,
        pkg: String = "de.norm.events.scraper.example"
    ) = rule.lint("package $pkg\n\nval LABEL = Regex($literal)\n", compile = false)

    @Test
    fun `reports every private spelling the venues had`() {
        listOf(
            """line\s*-?\s*up""",
            """line[ -]?up:?""",
            """^line[\s-]?up\s*:""",
            """line\s*up""",
            """line-?up""",
            """\bline[ -]?up\s*:""",
            """(?:LINE\s?-?\s?UP)"""
        ).forEach { pattern -> withClue(pattern) { findings("\"\"\"$pattern\"\"\"") shouldHaveSize 1 } }
    }

    @Test
    fun `reports a spelling in a plain string`() {
        findings("\"line\\\\s*up\"") shouldHaveSize 1
    }

    @Test
    fun `accepts the shared word and the plain text`() {
        listOf("^\$LINE_UP_WORD:", "Änderungen der Line-ups", "Line up", "deadline[0-9]+ups and downs")
            .forEach { pattern -> withClue(pattern) { findings("\"\"\"$pattern\"\"\"").shouldBeEmpty() } }
    }

    @Test
    fun `accepts a pattern outside the venue packages`() {
        findings("\"\"\"line\\s*-?\\s*up\"\"\"", pkg = "de.norm.events.scraper").shouldBeEmpty()
    }
}
