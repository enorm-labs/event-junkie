package de.norm.events.scraper

import de.norm.events.common.NameArbs
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Properties of the act splitters for any billing: `ActSplittingTest` and `ArtistNameMappingTest` pin the examples. */
class ActSplittingPropertyTest {
    @Test
    fun `every splitter returns at least one act, and a cut act is trimmed and never blank`() =
        runTest {
            checkAll(BILLING) { billing ->
                SPLITTERS.forEach { (name, split) ->
                    val acts = split(billing)
                    if (name != "splitSupportActs") acts.shouldNotBeEmpty()
                    if (billing.isNotBlank()) {
                        acts.forEach { act ->
                            act.shouldNotBeBlank()
                            if (act != billing) act shouldBe act.trim()
                        }
                    }
                }
            }
        }

    @Test
    fun `the cutting splitters return pieces of the billing`() =
        runTest {
            checkAll(BILLING) { billing ->
                listOf(::splitBackToBack, ::splitOnEmoji, ::splitCrossBilled, ::splitSegmentOnConjunctions).forEach { split ->
                    split(billing).forEach { act -> billing shouldContain act }
                }
            }
        }

    @Test
    fun `one plain name stays one act`() =
        runTest {
            checkAll(PLAIN_NAME) { name ->
                SPLITTERS.forEach { (_, split) -> split(name) shouldBe listOf(name) }
            }
        }

    @Test
    fun `plain names joined by b2b come back as the names`() =
        runTest {
            checkAll(PLAIN_NAMES, Arb.element(" b2b ", " B2B ", " b2b ")) { names, marker ->
                splitBackToBack(names.joinToString(marker)) shouldBe names
            }
        }

    @Test
    fun `plain names joined by emoji come back as the names`() =
        runTest {
            checkAll(PLAIN_NAMES, Arb.element(" 🔥 ", "⚡", " 🎤 ", " ❤️ ")) { names, emoji ->
                splitOnEmoji(names.joinToString(emoji)) shouldBe names
            }
        }

    @Test
    fun `plain names joined by a padded plus or slash come back as the headliners`() =
        runTest {
            checkAll(PLAIN_NAMES, Arb.element(" + ", " / ", "  +  ")) { names, separator ->
                splitHeadlinerTitle(names.joinToString(separator)) shouldBe names
            }
        }

    @Test
    fun `plain names joined by a comma, plus or slash come back as the support acts`() =
        runTest {
            checkAll(PLAIN_NAMES, Arb.element(", ", " + ", " / ", ",")) { names, separator ->
                splitSupportActs(names.joinToString(separator)) shouldBe names
            }
        }

    @Test
    fun `two plain names joined by a conjunction come back as two acts`() =
        runTest {
            checkAll(PLAIN_NAME, PLAIN_NAME, Arb.element(" & ", " and ", " und ", " AND ")) { first, second, conjunction ->
                val billing = "$first$conjunction$second"
                if (!isKnownSingleAct(billing)) {
                    splitSegmentOnConjunctions(billing) shouldBe listOf(first, second)
                    splitHeadlinerTitle(billing) shouldBe listOf(first, second)
                }
            }
        }

    @Test
    fun `splitting an act of a b2b or emoji line again changes nothing`() =
        runTest {
            checkAll(BILLING) { billing ->
                splitBackToBack(billing).forEach { act -> splitBackToBack(act) shouldBe listOf(act) }
                splitOnEmoji(billing).forEach { act -> splitOnEmoji(act) shouldBe listOf(act) }
            }
        }

    private companion object {
        val SPLITTERS: List<Pair<String, (String) -> List<String>>> =
            listOf(
                "splitBackToBack" to ::splitBackToBack,
                "splitOnEmoji" to ::splitOnEmoji,
                "splitCrossBilled" to ::splitCrossBilled,
                "splitSegmentOnConjunctions" to ::splitSegmentOnConjunctions,
                "splitSupportActs" to ::splitSupportActs,
                "splitHeadlinerTitle" to { title: String -> splitHeadlinerTitle(title) }
            )

        /** Words a splitter reads as a separator, a label or a band-name tail, so a plain name never holds one. */
        val RESERVED_WORDS: Set<String> =
            setOf("b2b", "and", "und", "meets", "invite", "invites", "feat", "featuring", "ft", "with", "mit", "vs", "presents", "live") +
                CONJUNCTION_TAIL_MARKERS.map { it.lowercase() }

        val WORD: Arb<String> =
            Arb
                .list(Arb.element(('a'..'z').toList()), 2..9)
                .map { letters -> letters.joinToString("").replaceFirstChar(Char::uppercaseChar) }
                .filter { it.lowercase() !in RESERVED_WORDS }

        /** A name of one to three words, letters only: nothing in it can read as a separator. */
        val PLAIN_NAME: Arb<String> = Arb.list(WORD, 1..3).map { it.joinToString(" ") }

        val PLAIN_NAMES: Arb<List<String>> = Arb.list(PLAIN_NAME, 2..5)

        private val SEPARATORS =
            listOf(" b2b ", " & ", " and ", " und ", " x ", " + ", " / ", ", ", " meets ", " invites ", " feat. ", " 🔥 ", " (", ") ", " - ", "•", " ")

        /** A billing built from plain names and every separator the splitters know, or any name at all. */
        val BILLING: Arb<String> =
            Arb.choice(
                Arb.bind(Arb.list(PLAIN_NAME, 1..5), Arb.list(Arb.element(SEPARATORS), 0..4)) { names, separators ->
                    names.zip(separators + List(names.size) { " " }).joinToString("") { (name, separator) -> name + separator }
                },
                NameArbs.anyName
            )
    }
}
