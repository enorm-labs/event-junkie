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

        stripContactDetails(text) shouldBe
            """
            David Rauterberg und Martin Hunger präsentieren die schönsten Lieder zum Mitsingen.

            Hinweis: Einlass ab 18:30 Uhr.
            """.trimIndent()
    }

    @Test
    fun `an address inside a sentence becomes the cut mark`() {
        stripContactDetails("Tickets gibt es an der Abendkasse oder unter tickets@venue.berlin, solange der Vorrat reicht.") shouldBe
            "Tickets gibt es an der Abendkasse oder unter […], solange der Vorrat reicht."
    }

    @Test
    fun `an address ends at the top-level label, so a word run into it stays`() {
        // Downstairs runs its lines together, so an address meets the next word (2026-10-26).
        stripContactDetails("Bei Fragen: info@example.deWhatsApp: siehe unten") shouldBe "Bei Fragen: […] WhatsApp: siehe unten"
        stripContactDetails("Stand? Meldet euch unter MARKT@EXAMPLE.BERLIN") shouldBe "Stand? Meldet euch unter […]"
        stripContactDetails("Mail: MAILTO:kontakt@example.De") shouldBe null
    }

    @Test
    fun `a text without an address comes back byte for byte, a handle included`() {
        val text = "Line-up: @sisyphos.berlin and DJ Foo\n\n  indented  "
        stripContactDetails(text) shouldBe text
    }

    @Test
    fun `a line that only gives a phone number goes, with its label`() {
        stripContactDetails("Einlass 19:30 Uhr.\nWhatsapp: 0163 1234567\nTel. 030 12345678") shouldBe "Einlass 19:30 Uhr."
    }

    @Test
    fun `a phone number inside a sentence becomes the cut mark, in each German form`() {
        stripContactDetails("Karten unter 030 / 123 456 78 oder an der Abendkasse.") shouldBe "Karten unter […] oder an der Abendkasse."
        stripContactDetails("Presse: Telefon +49 (0)30 123 456 78 und vor Ort") shouldBe "Presse: Telefon […] und vor Ort"
        stripContactDetails("Hotline 0180/5570070, täglich") shouldBe "Hotline […], täglich"
    }

    @Test
    fun `contacts side by side leave one cut mark`() {
        stripContactDetails("Büro: mail@example.de / 030 12345678 / Abendkasse ab 19 Uhr") shouldBe "Büro: […] / Abendkasse ab 19 Uhr"
    }

    @Test
    fun `a date, a time, a postcode, a code and a price are no phone number`() {
        val text = "Am 28.02.2027 um 19:30 Uhr, 10115 Berlin, EAN 4006381333931, Ticket 12,00 €, Saal 0 und Code 012345678901234567"
        stripContactDetails(text) shouldBe text
    }

    @Test
    fun `a text that is only an address leaves nothing`() {
        stripContactDetails("  kontakt@example.org  ").shouldBeNull()
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
