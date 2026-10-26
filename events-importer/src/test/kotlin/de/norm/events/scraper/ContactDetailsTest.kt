package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ContactDetailsTest {
    @Test
    fun `a line that only gives the address goes, with its label`() {
        // The shape of the Wühlmäuse show text that carried a performer's booking address (#3946).
        val text =
            """
            David Rauterberg und Martin Hunger präsentieren die schönsten Lieder zum Mitsingen.

            Buchungen für Schulen bitte per Mail an: booking.rauterberg@example.de
            E-Mail: mailto:info@example-kuenstler.de
            Hinweis: Einlass ab 18:30 Uhr.
            """.trimIndent()

        stripEmailAddresses(text) shouldBe
            """
            David Rauterberg und Martin Hunger präsentieren die schönsten Lieder zum Mitsingen.

            Hinweis: Einlass ab 18:30 Uhr.
            """.trimIndent()
    }

    @Test
    fun `an address inside a sentence goes and the sentence closes up`() {
        stripEmailAddresses("Tickets gibt es an der Abendkasse oder unter tickets@venue.berlin, solange der Vorrat reicht.") shouldBe
            "Tickets gibt es an der Abendkasse oder unter, solange der Vorrat reicht."
    }

    @Test
    fun `an address ends at the top-level label, so a word run into it stays`() {
        // Downstairs runs its lines together, so an address meets the next word (2026-10-26).
        stripEmailAddresses("Bei Fragen: info@example.deWhatsApp: siehe unten") shouldBe "Bei Fragen: WhatsApp: siehe unten"
        stripEmailAddresses("Stand? Meldet euch unter MARKT@EXAMPLE.BERLIN") shouldBe "Stand? Meldet euch unter"
        stripEmailAddresses("Mail: MAILTO:kontakt@example.De") shouldBe null
    }

    @Test
    fun `a text without an address comes back byte for byte, a handle included`() {
        val text = "Line-up: @sisyphos.berlin and DJ Foo\n\n  indented  "
        stripEmailAddresses(text) shouldBe text
    }

    @Test
    fun `a text that is only an address leaves nothing`() {
        stripEmailAddresses("  kontakt@example.org  ").shouldBeNull()
    }

    @Test
    fun `the stored description carries no address`() {
        val event =
            ScrapedEvent(
                title = "Rudelsingen",
                eventDate = LocalDate.of(2026, 10, 26),
                sourceUrl = "https://example.org/rudelsingen",
                sourceId = "x:1",
                description = "Lieder zum Mitsingen.\nBuchungen: booking@example.de"
            )

        val entity = event.toEventEntity(venueId = 1, venueSlug = "venue", eventSourceId = 1)

        entity.description shouldBe "Lieder zum Mitsingen."
    }
}
