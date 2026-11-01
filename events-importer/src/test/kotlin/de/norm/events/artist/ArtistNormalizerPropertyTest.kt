package de.norm.events.artist

import de.norm.events.common.NameArbs
import de.norm.events.common.foldTypedApostrophes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Properties of [canonicalArtistName] and [artistSlugFor] for any billing: [ArtistNormalizerTest] pins the examples. */
class ArtistNormalizerPropertyTest {
    @Test
    fun `normalizing a canonical name changes nothing`() =
        runTest {
            checkAll(NameArbs.anyName) { raw ->
                val canonical = canonicalArtistName(raw)
                canonicalArtistName(canonical) shouldBe canonical
            }
        }

    @Test
    fun `normalizing changes only letter case and spacing`() =
        runTest {
            checkAll(NameArbs.anyName) { raw ->
                val expected =
                    raw
                        .trim()
                        .foldTypedApostrophes()
                        .split(WHITESPACE)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                canonicalArtistName(raw).lowerCharWise() shouldBe expected.lowerCharWise()
            }
        }

    @Test
    fun `a name with a visible character never normalizes to blank`() =
        runTest {
            checkAll(NameArbs.anyName) { raw ->
                if (raw.isNotBlank()) canonicalArtistName(raw).shouldNotBeBlank()
            }
        }

    @Test
    fun `the artist slug ignores letter case and spacing`() =
        runTest {
            checkAll(NameArbs.latinName) { raw ->
                val slug = artistSlugFor(raw)
                artistSlugFor(raw.uppercase()) shouldBe slug
                artistSlugFor(raw.lowercase()) shouldBe slug
                artistSlugFor("  ${raw.replace(" ", "  ")} ") shouldBe slug
            }
        }

    // Char by char, as the de-shouter lowers: `String.lowercase()` turns `İ` into two characters, where the Turkish `i` is one.
    private fun String.lowerCharWise(): String = map { it.lowercaseChar() }.joinToString("")

    private companion object {
        val WHITESPACE = Regex("""\s+""")
    }
}
