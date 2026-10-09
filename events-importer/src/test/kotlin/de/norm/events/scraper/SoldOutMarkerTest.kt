package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

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
            "SingAlong – Das große Mitsing-Event-ausverkauft-",
            "AUSVERKAUFT: SOKO LiNX - PUNK FÜR LEUTE",
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
            "Konzert-ausverkauft",
            "Mitsing-Event-ausverkauften-",
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
        stripSoldOutMarker("SingAlong – Das große Mitsing-Event-ausverkauft-") shouldBe "SingAlong – Das große Mitsing-Event"
        stripSoldOutMarker("Dan Docimo – LATE SHOW (SOLD OUT!!!)") shouldBe "Dan Docimo – LATE SHOW"
        stripSoldOutMarker("AUSVERKAUFT") shouldBe "AUSVERKAUFT"
    }

    // #2971: the marker leaves the stored title and its slug, and the flag stays set.
    @Test
    fun `toEventEntity strips a sold-out marker from the title and the slug, and marks the row sold out`() {
        listOf(
            Triple(
                "schokoladen",
                "AUSVERKAUFT: SOKO LiNX - PUNK FÜR LEUTE, DIE PUNK HASZEN - TOUR 2026 + leavr (Leipzig)",
                "SOKO LiNX - PUNK FÜR LEUTE, DIE PUNK HASZEN - TOUR 2026 + leavr (Leipzig)"
            ) to "2026-12-30-schokoladen-soko-linx-punk-fur-leute-die-punk-haszen-tour-2026-leavr-leipzig",
            Triple(
                "kulturhaus-peter-edel",
                "Talkshow \"Woher Wohin - Veronika Fischer persönlich\" [AUSVERKAUFT!]",
                "Talkshow \"Woher Wohin - Veronika Fischer persönlich\""
            ) to "2026-12-30-kulturhaus-peter-edel-talkshow-woher-wohin-veronika-fischer-personlich",
            Triple(
                "cosmic-comedy-club",
                "Dan Docimo – Live in Berlin – LATE SHOW (SOLD OUT!!!)",
                "Dan Docimo – Live in Berlin – LATE SHOW"
            ) to "2026-12-30-cosmic-comedy-club-dan-docimo-live-in-berlin-late-show",
            Triple(
                "frannz-club",
                "SingAlong – Das große Mitsing-Event-ausverkauft-",
                "SingAlong – Das große Mitsing-Event"
            ) to "2026-12-30-frannz-club-singalong-das-grosse-mitsing-event"
        ).forEach { (venue, slug) ->
            val (venueSlug, raw, stored) = venue
            val entity = scrapedEvent(raw).toEventEntity(venueId = 1L, venueSlug = venueSlug, eventSourceId = 1L)

            entity.title shouldBe stored
            entity.slug shouldBe slug
            entity.soldOut shouldBe true
        }
    }

    @Test
    fun `toEventEntity strips a sold-out marker before it strips a status note`() {
        val entity = scrapedEvent("Reimanns (ausverkauft) - Abgesagt").toEventEntity(venueId = 1L, venueSlug = "so36", eventSourceId = 1L)

        entity.title shouldBe "Reimanns"
        entity.status shouldBe "CANCELLED"
    }

    private fun scrapedEvent(title: String) =
        ScrapedEvent(
            title = title,
            eventDate = LocalDate.of(2026, 12, 30),
            sourceId = "so36:98223",
            sourceUrl = "https://www.so36.com/produkte/98223"
        )
}
