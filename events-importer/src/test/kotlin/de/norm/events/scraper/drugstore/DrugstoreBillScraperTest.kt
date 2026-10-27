package de.norm.events.scraper.drugstore

import de.norm.events.event.ArtistRole
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
                "Finalizer" to ArtistRole.HEADLINER,
                "Pyre" to ArtistRole.SUPPORT,
                "Velocyraptor" to ArtistRole.SUPPORT,
                "No Face 103" to ArtistRole.SUPPORT,
                "Stray Dogs" to ArtistRole.SUPPORT,
                "Meant to burn" to ArtistRole.SUPPORT
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
    fun `splits at a plus followed by a non-breaking space, as radar writes it`() {
        val bill =
            parseDrugstoreBill(
                "w/\u00A0Ploutonion (DeathMetal // Berlin) +\u00A0Krachmaninov (Blackened Crust // Berlin) +\u00A0" +
                    "Teufelnacht (Teufelrock // Münster) +\u00A0Frost, Pisse, Elend (Antifascist BlackMetal // Kiel)"
            )
        bill.artists.map { it.name to it.role } shouldBe
            listOf(
                "Ploutonion" to ArtistRole.HEADLINER,
                "Krachmaninov" to ArtistRole.SUPPORT,
                "Teufelnacht" to ArtistRole.SUPPORT,
                "Frost, Pisse, Elend" to ArtistRole.SUPPORT
            )
        bill.genre shouldBe "DeathMetal, Blackened Crust, Teufelrock, Antifascist BlackMetal"
    }

    @Test
    fun `keeps every act after a non-breaking space, the unbracketed last one included`() {
        val bill =
            parseDrugstoreBill(
                "w/\u00A0Stoned Medusa (Psych Doom // Berlin) +\u00A0Acid Throne (Blackened Doom // UK) +\u00A0" +
                    "Witchrot // Fuzzed out PsychDoom // Kanada"
            )
        bill.artists.map { it.name } shouldBe listOf("Stoned Medusa", "Acid Throne", "Witchrot")
    }

    @Test
    fun `splits at a non-breaking space before the plus as well`() {
        val bill = parseDrugstoreBill("Featuring: Finalizer (Hardcore)\u00A0+ Pyre (Hardcore)")
        bill.artists.map { it.name } shouldBe listOf("Finalizer", "Pyre")
    }

    @Test
    fun `bills no one for prose without a bill line`() {
        val bill = parseDrugstoreBill("CAYENNE + SEITENSPRUNG AM DIENSTAG\nBeginn: 19 Uhr\nEintritt frei!")
        bill.artists.shouldBeEmpty()
        bill.genre.shouldBeNull()
        parseDrugstoreBill(null).artists.shouldBeEmpty()
    }
}
