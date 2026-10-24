package de.norm.events.scraper.drugstore

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Unit tests for [parseDrugstoreBill], on descriptions from Drugstore's radar group. */
class DrugstoreBillScraperTest {
    @Test
    fun `bills each act on the Featuring line, the first as headliner`() {
        val bill =
            parseDrugstoreBill(
                "Hardcore for Solidarity - PYRE & NO FACE 103 present:\nSoli Mosh Berlin\n\nFREE ENTRY\n\n" +
                    "Featuring: Finalizer (Hardcore) + Pyre (Hardcore) + Velocyraptor (Beatdown) + No Face 103 (Hardcore) + " +
                    "Stray Dogs (Hardcore) + Meant to burn (Hardcore) \n\nDoors 18 | Start 19"
            )
        bill.artists.map { it.name to it.role } shouldBe
            listOf(
                "Finalizer" to "HEADLINER",
                "Pyre" to "SUPPORT",
                "Velocyraptor" to "SUPPORT",
                "No Face 103" to "SUPPORT",
                "Stray Dogs" to "SUPPORT",
                "Meant to burn" to "SUPPORT"
            )
        bill.genre shouldBe "Hardcore, Beatdown"
    }

    @Test
    fun `reads the w-slash line, with the home town after a double slash and an act without a bracket`() {
        val bill =
            parseDrugstoreBill(
                "D.I.Y. Underground Indoor Festival\nDoors: 7pm\nStart: 8:30pm\n" +
                    "w/ Stoned Medusa (Psych Doom // Berlin) + Acid Throne (Blackened Doom // UK) + Witchrot // Fuzzed out PsychDoom // Kanada\n" +
                    "FCK NSBM"
            )
        bill.artists.map { it.name } shouldBe listOf("Stoned Medusa", "Acid Throne", "Witchrot")
        bill.genre shouldBe "Psych Doom, Blackened Doom, Fuzzed out PsychDoom"
    }

    @Test
    fun `keeps a single slash and a comma inside the genre or the name`() {
        val bill = parseDrugstoreBill("w/ Sternenwarte (Dungeon Synth / Noise // Berlin) + Frost, Pisse, Elend (Antifascist BlackMetal // Kiel)")
        bill.artists.map { it.name } shouldBe listOf("Sternenwarte", "Frost, Pisse, Elend")
        bill.genre shouldBe "Dungeon Synth / Noise, Antifascist BlackMetal"
    }

    @Test
    fun `bills no one for prose without a bill line`() {
        val bill = parseDrugstoreBill("CAYENNE + SEITENSPRUNG AM DIENSTAG\nBeginn: 19 Uhr\nEintritt frei!")
        bill.artists.shouldBeEmpty()
        bill.genre.shouldBeNull()
        parseDrugstoreBill(null).artists.shouldBeEmpty()
    }
}
