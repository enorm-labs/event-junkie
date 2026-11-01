package de.norm.events.promoter

import de.norm.events.common.NameArbs
import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.shouldBe
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

/** Properties of [canonicalPromoterName] and [isNonPromoterName] for any credit: [PromoterNormalizerTest] pins the examples. */
class PromoterNormalizerPropertyTest {
    @Test
    fun `normalizing a canonical name changes nothing`() =
        runTest {
            checkAll(CREDIT) { raw ->
                val canonical = canonicalPromoterName(raw)
                canonicalPromoterName(canonical) shouldBe canonical
            }
        }

    @Test
    fun `a credit with a visible character never normalizes to blank`() =
        runTest {
            checkAll(CREDIT) { raw ->
                if (raw.isNotBlank()) canonicalPromoterName(raw).shouldNotBeBlank()
            }
        }

    @Test
    fun `the promoter slug ignores spacing`() =
        runTest {
            checkAll(CREDIT) { raw ->
                slug("  ${raw.replace(" ", "  ")}\t") shouldBe slug(raw)
            }
        }

    @Test
    fun `the promoter slug ignores letter case when no word could be an initialism`() =
        runTest {
            checkAll(NAME, Arb.list(Arb.element(SUFFIXES), 0..2)) { name, suffixes ->
                val credit = (listOf(name) + suffixes).joinToString(" ")
                slug(credit.uppercase()) shouldBe slug(credit)
                slug(credit.lowercase()) shouldBe slug(credit)
            }
        }

    @Test
    fun `a trailing legal form or descriptor folds into the bare name`() =
        runTest {
            checkAll(NAME, Arb.list(Arb.element(SUFFIXES), 1..3)) { name, suffixes ->
                canonicalPromoterName((listOf(name) + suffixes).joinToString(" ")) shouldBe canonicalPromoterName(name)
            }
        }

    @Test
    fun `a credit of legal forms and descriptors alone is no promoter`() =
        runTest {
            checkAll(Arb.list(Arb.element(SUFFIXES), 1..4)) { words ->
                isNonPromoterName(words.joinToString(" ")) shouldBe true
            }
        }

    @Test
    fun `a credit that names a promoter slugs to something`() =
        runTest {
            checkAll(Arb.choice(CREDIT, NameArbs.latinName)) { raw ->
                if (!isNonPromoterName(raw) && raw.any(Char::isLetterOrDigit)) slug(raw).shouldNotBeBlank()
            }
        }

    private fun slug(raw: String): String = SlugGenerator.slugify(canonicalPromoterName(raw))

    private companion object {
        /** Trailing words the normalizer strips, in the spellings credits use. */
        val SUFFIXES =
            listOf(
                "GmbH",
                "GMBH",
                "gGmbH",
                "UG",
                "e.K.",
                "KG",
                "Concerts",
                "CONCERTS",
                "Konzerte",
                "Music",
                "Musik",
                "Events",
                "Booking",
                "Agency",
                "Entertainment",
                "Live",
                "Records",
                "Productions",
                "&",
                "und",
                "and",
                "präsentiert:",
                "prsnt:"
            )

        /** Four letters or more in every word, so no word is an initialism the guard keeps capitals or a descriptor for. */
        private val WORD: Arb<String> =
            Arb
                .list(Arb.element(('a'..'z').toList()), 4..9)
                .map { letters -> letters.joinToString("").replaceFirstChar(Char::uppercaseChar) }
                .filter { word -> SUFFIXES.none { it.equals(word, ignoreCase = true) } }

        val NAME: Arb<String> = Arb.list(WORD, 1..3).map { it.joinToString(" ") }

        private val INITIALISM: Arb<String> = Arb.element("HB", "MFP", "B&C", "KKT", "TV", "DJ", "Jb", "hb")

        private val TOKEN: Arb<String> =
            Arb.choice(
                WORD,
                WORD.map(String::uppercase),
                INITIALISM,
                Arb.element(SUFFIXES),
                Arb.element("36", "(wf)", "(GSA)", "„Aufgeigen.at“", "\"Loft\"", "O’Neil", "–", "x", "Ar")
            )

        /** A credit built from names, initialisms, legal forms, descriptors, annotations and quotes, or any name at all. */
        val CREDIT: Arb<String> =
            Arb.choice(
                Arb.bind(Arb.list(TOKEN, 1..5), Arb.element(" ", "  ", " \t")) { tokens, space -> tokens.joinToString(space) },
                NameArbs.anyName
            )
    }
}
