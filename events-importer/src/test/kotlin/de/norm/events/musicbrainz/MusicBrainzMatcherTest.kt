package de.norm.events.musicbrainz

import de.norm.events.artist.MusicBrainzMatch
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The match rule, on the candidate lists `scripts/musicbrainz-match.py` recorded for the names
 * ADR-031 was decided on. Nothing here reaches MusicBrainz.
 */
class MusicBrainzMatcherTest {
    private fun candidate(
        name: String,
        id: String = "id-$name",
        country: String? = null,
        sortName: String? = null,
        aliases: List<String> = emptyList()
    ) = MusicBrainzCandidate(id = id, name = name, sortName = sortName, country = country, aliases = aliases.map(::MusicBrainzAlias))

    @Nested
    inner class Verdicts {
        @Test
        fun `Pici is NONE, whatever the score of Pici Mazzei`() {
            MusicBrainzMatcher.decide("Pici", listOf(candidate("Pici Mazzei", country = "IT"))) shouldBe MusicBrainzVerdict.NONE
        }

        @Test
        fun `Andy is AMBIGUOUS, because only an alias of Horace Andy carries the name`() {
            val candidates =
                listOf(
                    candidate("Andy Williams", country = "US"),
                    candidate("Horace Andy", country = "JM", aliases = listOf("Andy")),
                    candidate("Andy Summers", country = "GB")
                )
            MusicBrainzMatcher.decide("Andy", candidates) shouldBe MusicBrainzVerdict.AMBIGUOUS
        }

        @Test
        fun `Accept is EXACT by country=DE among two groups of that name`() {
            val candidates =
                listOf(
                    candidate("Accept", id = "41f4d85a-0bd7-4602-a3e3-8c47f36efb0a", country = "DE"),
                    candidate("ACCEPT", id = "jp", country = "JP"),
                    candidate("Accept Death", country = "US")
                )
            MusicBrainzMatcher.decide("Accept", candidates) shouldBe
                MusicBrainzVerdict(MusicBrainzMatch.EXACT, "41f4d85a-0bd7-4602-a3e3-8c47f36efb0a")
        }

        @Test
        fun `Kein Bock auf Nazis is NONE`() {
            MusicBrainzMatcher.decide("Kein Bock auf Nazis", emptyList()) shouldBe MusicBrainzVerdict.NONE
        }

        @Test
        fun `one candidate with the name is EXACT without a tie-break`() {
            MusicBrainzMatcher.decide("Andy Kolwes", listOf(candidate("Andy Kolwes", id = "53e9e980"))) shouldBe
                MusicBrainzVerdict(MusicBrainzMatch.EXACT, "53e9e980")
        }

        @Test
        fun `several with the name and none or several from Germany is AMBIGUOUS`() {
            val abroad = listOf(candidate("Andy Martin", id = "mx", country = "MX"), candidate("Andy Martin", id = "us", country = "US"))
            MusicBrainzMatcher.decide("Andy Martin", abroad) shouldBe MusicBrainzVerdict.AMBIGUOUS

            val twoGerman = listOf(candidate("Echo", id = "de1", country = "DE"), candidate("Echo", id = "de2", country = "DE"))
            MusicBrainzMatcher.decide("Echo", twoGerman) shouldBe MusicBrainzVerdict.AMBIGUOUS
        }

        @Test
        fun `sort-name equality alone is AMBIGUOUS`() {
            val candidates = listOf(candidate("The Beatles", sortName = "Beatles, The"))
            MusicBrainzMatcher.decide("Beatles, The", candidates) shouldBe MusicBrainzVerdict.AMBIGUOUS
        }

        @Test
        fun `a name that differs only in case or accents is EXACT`() {
            MusicBrainzMatcher.decide("motörhead", listOf(candidate("Motörhead", id = "mh"))) shouldBe
                MusicBrainzVerdict(MusicBrainzMatch.EXACT, "mh")
            MusicBrainzMatcher.decide("Motorhead", listOf(candidate("Motörhead", id = "mh"))) shouldBe
                MusicBrainzVerdict(MusicBrainzMatch.EXACT, "mh")
        }
    }

    @Nested
    inner class Fold {
        @Test
        fun `reads and, und and the ampersand as one word`() {
            MusicBrainzMatcher.fold("Simon and Garfunkel") shouldBe "simon & garfunkel"
            MusicBrainzMatcher.fold("Simon & Garfunkel") shouldBe "simon & garfunkel"
            MusicBrainzMatcher.fold("Simon und Garfunkel") shouldBe "simon & garfunkel"
        }

        @Test
        fun `collapses whitespace and drops trailing punctuation`() {
            MusicBrainzMatcher.fold("  Die  Ärzte! ") shouldBe "die arzte"
            MusicBrainzMatcher.fold("Panic! At the Disco") shouldBe "panic! at the disco"
        }
    }

    @Nested
    inner class Head {
        @Test
        fun `the part before a spaced dash or a colon is the head`() {
            MusicBrainzMatcher.headOf("Andy Strauß - Dosenpfandbetrug") shouldBe "Andy Strauß"
            MusicBrainzMatcher.headOf("Blvsh – Live") shouldBe "Blvsh"
            MusicBrainzMatcher.headOf("Dionys: Hardtechno x Trance") shouldBe "Dionys"
        }

        @Test
        fun `no separator, an empty side, or a digits-only head is no head`() {
            MusicBrainzMatcher.headOf("Kein Bock auf Nazis").shouldBeNull()
            MusicBrainzMatcher.headOf("Cut:na").shouldBeNull()
            MusicBrainzMatcher.headOf("2 - Set").shouldBeNull()
            MusicBrainzMatcher.headOf("- Set").shouldBeNull()
        }
    }
}
