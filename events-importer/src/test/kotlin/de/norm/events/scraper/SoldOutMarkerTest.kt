package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SoldOutMarkerTest {
    @Test
    fun `reads every marker shape the venues write`() {
        listOf(
            "!!AUSVERKAUFT!! Tim Vantol",
            "!!SOLD OUT!! Tim Vantol",
            "SOLD OUT - Otha",
            "(ausverkauft) The Act",
            "Please Louise [ausverkauft]",
            "Please Louise [pre-sale sold out, 10 tix on the doors]",
            "SukOne [AUSVERKAUFT!]",
            "Dan Docimo – Live in Berlin – LATE SHOW (SOLD OUT!!!)",
            "Haken -ausverkauft-",
            "Singalong (ausverkauft)",
            "Flower Face SOLD OUT",
            "DOTAN (ausverkauft)",
            "Tenside - ausgebucht",
            "The Blood Arm [ausverauft]",
            "„Sad Girl Summer TOUR 2026\" DAS KONZERT IST RESTLOS AUSVERKAUFT UND ES WIRD KEINE TICKETS AN DER ABENDKASSE GEBEN!",
            "This show is sold out"
        ).forEach { (it to hasSoldOutMarker(it)) shouldBe (it to true) }
    }

    @Test
    fun `leaves a name, a tour title and a sentence about a record alone`() {
        listOf(
            "Sold Out Tour 2026",
            "The Act (Sold Out Tour)",
            "Ihre restlos ausverkauften Konzerte",
            "the tape sold out in a record 3 months",
            "Soldouts live",
            "Ausverkauft war gestern: ein Abend mit X",
            null
        ).forEach { (it to hasSoldOutMarker(it)) shouldBe (it to false) }
    }

    @Test
    fun `strips the marker and leaves the rest of the title`() {
        stripSoldOutMarker("!!AUSVERKAUFT!! Tim Vantol") shouldBe "Tim Vantol"
        stripSoldOutMarker("SOLD OUT - Otha") shouldBe "Otha"
        stripSoldOutMarker("The Blood Arm + Please Louise [ausverkauft]") shouldBe "The Blood Arm + Please Louise"
        stripSoldOutMarker("Flower Face SOLD OUT") shouldBe "Flower Face"
        stripSoldOutMarker("Haken -ausverkauft-") shouldBe "Haken"
        stripSoldOutMarker("Dan Docimo – LATE SHOW (SOLD OUT!!!)") shouldBe "Dan Docimo – LATE SHOW"
        stripSoldOutMarker("AUSVERKAUFT") shouldBe "AUSVERKAUFT"
    }
}
