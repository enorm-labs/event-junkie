package de.norm.events.scraper.koepi

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Unit tests for [parseKoepiBill], on descriptions from KØPI's radar group. */
class KoepiBillScraperTest {
    @Test
    fun `bills each quoted act with its bracket, the first as headliner`() {
        val bill = parseKoepiBill("\"Death Gasp\"\n(Stench Crust, Pittsburgh, US) -\n\"Krime\"\n(Hardcore Punk, Netherlands) -\n\"Sense\"\n(Punk, Bremen)")
        bill.artists.map { it.name to it.role } shouldBe
            listOf("Death Gasp" to "HEADLINER", "Krime" to "SUPPORT", "Sense" to "SUPPORT")
        bill.genre shouldBe "Stench Crust, Hardcore Punk, Punk"
    }

    @Test
    fun `skips the night's name and a slogan, and bills a DJ slot by the act in its bracket`() {
        val bill =
            parseKoepiBill(
                "\"B-day Bash - Dirty Immigrant & FU-F. Part II - Punk concerts\" - \"HOSTIUM\" (Trash Punk / HC, Bogotá, Colombia) - " +
                    "\"FUNERAL DAMAGE\" (Crust, Berlin) - \"PUNK DJs\" (BALADA GANGSTER) - \"OUR ROOTS ARE STRONGER THAN YOUR CHAINS.\""
            )
        bill.artists.map { it.name to it.role } shouldBe
            listOf("HOSTIUM" to "HEADLINER", "FUNERAL DAMAGE" to "SUPPORT", "BALADA GANGSTER" to "DJ")
        bill.genre shouldBe "Trash Punk / HC, Crust"
    }

    @Test
    fun `reads a bracket with no home town and one with a stray space before the comma`() {
        val bill = parseKoepiBill("\"Sadistic Goatmessiah\"\n(Speed Black Metal) -\n\"Paranoia Regime\"\n(HC Punk ,Berlin)")
        bill.artists.map { it.name } shouldBe listOf("Sadistic Goatmessiah", "Paranoia Regime")
        bill.genre shouldBe "Speed Black Metal, HC Punk"
    }

    @Test
    fun `bills no one for a description without a quoted act`() {
        val bill = parseKoepiBill("Soli-Tresen mit Musik")
        bill.artists.shouldBeEmpty()
        bill.genre.shouldBeNull()
        parseKoepiBill(null).artists.shouldBeEmpty()
    }
}
