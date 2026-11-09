package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** Properties of [descriptionText] for any mix of blocks, inline elements and `<br>`: [ScrapingExtensionsTest] pins the examples. */
class DescriptionTextPropertyTest {
    @Test
    fun `the lines read with spaces are the element's flat text, so no word is lost or added`() =
        runTest {
            checkAll(HTML) { html ->
                val body = Jsoup.parseBodyFragment(html).body()
                body.descriptionText()?.replace('\n', ' ').orEmpty() shouldBe body.text()
            }
        }

    @Test
    fun `no line is blank or carries surrounding whitespace`() =
        runTest {
            checkAll(HTML) { html ->
                Jsoup
                    .parseBodyFragment(html)
                    .body()
                    .descriptionText()
                    ?.lines()
                    ?.forEach { line -> line shouldBe line.trim().ifEmpty { "<blank>" } }
            }
        }

    private companion object {
        val WORD: Arb<String> = Arb.string(1..8, Codepoint.alphanumeric())

        /** A word, a break, or a word wrapped in an inline or a block element. */
        val PIECE: Arb<String> =
            Arb.choice(
                WORD,
                WORD.map { " $it " },
                Arb.element("<br>", "<br><br>", " ", "<p> </p>"),
                WORD.map { "<strong>$it</strong>" },
                WORD.map { "<span>$it<br>$it</span>" },
                WORD.map { "<p>$it</p>" },
                WORD.map { "<div>$it <em>$it</em></div>" },
                WORD.map { "<ul><li>$it</li><li>$it</li></ul>" }
            )

        val HTML: Arb<String> = Arb.list(PIECE, 0..12).map { it.joinToString("") }
    }
}
