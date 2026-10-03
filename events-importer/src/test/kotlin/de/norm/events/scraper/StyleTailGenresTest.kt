package de.norm.events.scraper

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** [knownGenresInStyleTail] against style tails Arcanoa and ART Stalker published (#2397). */
class StyleTailGenresTest {
    @Test
    fun `splits a run-together tail into the genres it names`() {
        assertSoftly {
            mapOf(
                "EthnoBluesJazzAfroLatinFolkSession" to "Blues, Jazz, Afrobeats, Latin, Folk",
                "RockPopFunkBlues" to "Rock, Pop, Funk, Blues",
                "HardRockPunk" to "Rock, Punk",
                "CountryBluesRock" to "Blues, Rock"
            ).forEach { (tail, genres) -> withClue(tail) { knownGenresInStyleTail(tail) shouldBe genres } }
        }
    }

    @Test
    fun `looks a part up whole before splitting it, so a compound keeps its own genre`() {
        knownGenresInStyleTail("PostPunk") shouldBe "Post-Punk"
    }

    @Test
    fun `reads the styles beside a support act and drops the act`() {
        knownGenresInStyleTail("PopRockAltern. + Vetro - PopPunk") shouldBe "Pop, Rock, Punk"
    }

    @Test
    fun `reads the genre words out of a tagline and drops the prose`() {
        assertSoftly {
            mapOf(
                "Jazz / Funk / Groove / Swing / Blues - Moderation Gaspare" to "Jazz, Funk, Blues",
                "Blues, Rock, Pop, Funk für jeden was dabei!" to "Blues, Rock, Pop, Funk",
                "Indie Pop/Rock - Ein Abend für alle, die wissen" to "Indie, Rock"
            ).forEach { (tail, genres) -> withClue(tail) { knownGenresInStyleTail(tail) shouldBe genres } }
        }
    }

    @Test
    fun `reads nothing from a tail that names no genre`() {
        assertSoftly {
            listOf("Support Act: SMILING SUN SONS", "das spannende Musikquiz mit eavo", "freie Bühne - SpielleuteSession", "open stage")
                .forEach { tail -> withClue(tail) { knownGenresInStyleTail(tail).shouldBeNull() } }
        }
        knownGenresInStyleTail(null).shouldBeNull()
    }
}
