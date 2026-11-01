package de.norm.events.genretag

import de.norm.events.common.NameArbs
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainDuplicates
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Properties of [normalizeGenre] and [nonGenreTokens] for any genre string: [GenreNormalizerTest] pins the examples. */
class GenreNormalizerPropertyTest {
    @Test
    fun `every canonical genre normalizes to itself, in any letter case`() {
        assertSoftly {
            CANONICAL.forEach { genre ->
                listOf(genre, genre.uppercase(), genre.lowercase()).forEach { written -> normalizeGenre(written) shouldBe listOf(genre) }
            }
        }
    }

    @Test
    fun `normalizing a normalized tag changes nothing`() =
        runTest {
            checkAll(GENRE_TEXT) { raw ->
                normalizeGenre(raw).forEach { tag -> normalizeGenre(tag) shouldBe listOf(tag) }
            }
        }

    @Test
    fun `the tags are distinct, never blank, and start with a capital where the letter has one`() =
        runTest {
            checkAll(GENRE_TEXT) { raw ->
                val tags = normalizeGenre(raw)
                tags.shouldNotContainDuplicates()
                tags.forEach { tag ->
                    tag.shouldNotBeBlank()
                    tag.first() shouldBe tag.first().uppercaseChar()
                }
            }
        }

    @Test
    fun `known genres joined by any delimiter give the tags of each`() =
        runTest {
            checkAll(Arb.list(KNOWN, 1..4), Arb.element(DELIMITERS)) { genres, delimiter ->
                normalizeGenre(genres.joinToString(delimiter)) shouldBe genres.flatMap(::normalizeGenre).distinct()
            }
        }

    @Test
    fun `known genres ignore letter case and spacing`() =
        runTest {
            checkAll(Arb.list(KNOWN, 1..4), Arb.element(DELIMITERS)) { genres, delimiter ->
                val raw = genres.joinToString(delimiter)
                val tags = normalizeGenre(raw)
                normalizeGenre(raw.uppercase()) shouldBe tags
                normalizeGenre(raw.lowercase()) shouldBe tags
                normalizeGenre("  ${raw.replace(" ", "  ")} ") shouldBe tags
            }
        }

    @Test
    fun `a dropped token names no genre on its own`() =
        runTest {
            checkAll(GENRE_TEXT) { raw ->
                nonGenreTokens(raw).forEach { token -> normalizeGenre(token).shouldBeEmpty() }
            }
        }

    @Test
    fun `a known genre survives noise around it`() =
        runTest {
            checkAll(KNOWN, Arb.element(NOISE)) { genre, noise ->
                normalizeGenre("$genre, $noise") shouldContainAll normalizeGenre(genre)
            }
        }

    private companion object {
        val CANONICAL: List<String> = GENRE_SYNONYMS.values.distinct()

        /** A known genre as a canonical name or as one of the spellings the synonym map keys. */
        val KNOWN: Arb<String> = Arb.choice(Arb.element(CANONICAL), Arb.element(GENRE_SYNONYMS.keys.toList()))

        val DELIMITERS = listOf(", ", ",", " / ", "/", " // ", " & ", " or ", " oder ", " vs ", " und ")

        /** Formats, times and fillers a genre field carries beside the genres. */
        val NOISE = listOf("Open Air", "Release Party", "from 8pm", "etc.", "x", "Special", "Floor", "Twenty One Pilots Special")

        private val TOKEN: Arb<String> =
            Arb.choice(KNOWN, KNOWN.map(String::uppercase), Arb.element(NOISE), NameArbs.latinName)

        /** Genre fields built from known genres, noise and arbitrary names, joined by the delimiters, or any name at all. */
        val GENRE_TEXT: Arb<String> =
            Arb.choice(
                Arb.bind(Arb.list(TOKEN, 1..5), Arb.element(DELIMITERS)) { tokens, delimiter -> tokens.joinToString(delimiter) },
                NameArbs.anyName
            )
    }
}
