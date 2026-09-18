package de.norm.events.scraper.soda

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [SodaDetailPageScraper].
 *
 * Uses real detail-page snapshots covering the three pricing shapes the venue renders: a
 * paid night sold both online and at the door, an open air sold online only (no
 * "Abendkasse verfügbar" badge), and a free resident night (0 € admission, no ticket
 * shop). Sold-out and title-fallback handling are exercised with minimal inline pages,
 * neither of which the venue was showing when the snapshots were taken.
 */
class SodaDetailPageScraperTest {
    private val scraper = SodaDetailPageScraper()

    private fun parse(
        fixture: String,
        url: String
    ): ScrapedEvent? {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/soda/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url), url)
    }

    private fun parseHtml(
        html: String,
        url: String = "https://www.soda-berlin.de/de/events/inline"
    ): ScrapedEvent? = scraper.scrape(Jsoup.parse(html, url), url)

    @Test
    fun `parses all detail fields for a night sold online and at the door`() {
        val url = "https://www.soda-berlin.de/de/events/famous-friday-31-07-2026"
        val event = parse("soda-detail-famous-friday.html", url).shouldNotBeNull()

        event.title shouldBe "Famous Friday"
        event.eventType shouldBe "PARTY"
        event.eventDate shouldBe LocalDate.of(2026, 7, 31)
        event.startTime shouldBe LocalTime.of(22, 0)
        // The "Einlass" box states an age limit ("Ab 18"), not a doors time.
        event.doorsTime.shouldBeNull()
        event.status shouldBe "SCHEDULED"
        event.soldOut shouldBe false
        event.free shouldBe false
        event.sourceUrl shouldBe url
        event.sourceId shouldBe "soda:famous-friday-31-07-2026"
        event.ticketUrl shouldBe "https://www.soda-berlin.de/de/events/famous-friday-31-07-2026#tickets"
        event.imageUrl shouldBe "https://soda.disco2app.com/media/events/828/image/23623"
        // The "Eintritt" box is the venue's price for both slots; the shop's fee-inclusive offer
        // stays out of the columns and in the note (#1583).
        event.pricePresale shouldBe BigDecimal("15")
        event.priceBoxOffice shouldBe BigDecimal("15")
        event.priceNote shouldBe "online 15,43 € inkl. Gebühren"
        // The full blurb comes from the markup, not the truncated JSON-LD description.
        event.description.shouldNotBeNull() shouldStartWith "🔥 Achtung Ladies & Gentlemen:"
        event.description.shouldNotBeNull() shouldContain "Booking"
        // Soda bills no acts — the JSON-LD performer is the placeholder "Unbekannt".
        event.artists.shouldBeEmpty()
        event.promoters.shouldBeEmpty()
    }

    @Test
    fun `a night sold online and at the door survives the persistence boundary with the door no dearer than presale`() {
        val url = "https://www.soda-berlin.de/de/events/famous-friday-31-07-2026"
        val entity = parse("soda-detail-famous-friday.html", url).shouldNotBeNull().toEventEntity(venueId = 1L, venueSlug = "soda", eventSourceId = 1L)

        entity.pricePresale shouldBe BigDecimal("15.00")
        entity.priceBoxOffice shouldBe BigDecimal("15.00")
        entity.priceNote shouldBe "online 15,43 € inkl. Gebühren"
        entity.free shouldBe false
    }

    @Test
    fun `records the admission price as presale when the venue offers no door sales`() {
        val url = "https://www.soda-berlin.de/de/events/ballermann-open-air-150826"
        val event = parse("soda-detail-open-air.html", url).shouldNotBeNull()

        event.eventDate shouldBe LocalDate.of(2026, 8, 15)
        event.startTime shouldBe LocalTime.of(14, 0)
        event.sourceId shouldBe "soda:ballermann-open-air-150826"
        // No "Abendkasse verfügbar" badge, so the 25 € admission is not a door price;
        // it is the presale price, and the fee-inclusive shop figure is only noted.
        event.pricePresale shouldBe BigDecimal("25")
        event.priceBoxOffice.shouldBeNull()
        event.priceNote shouldBe "online 27,17 € inkl. Gebühren"
        event.free shouldBe false
    }

    @Test
    fun `flags a free resident night from its zero euro admission`() {
        val url = "https://www.soda-berlin.de/de/events/salsa-sonntag-02-08-2026"
        val event = parse("soda-detail-free.html", url).shouldNotBeNull()

        event.title shouldBe "Salsa Sonntag"
        event.eventDate shouldBe LocalDate.of(2026, 8, 2)
        event.startTime shouldBe LocalTime.of(18, 0)
        event.free shouldBe true
        event.priceBoxOffice shouldBe BigDecimal("0")
        event.pricePresale.shouldBeNull()
        event.priceNote.shouldBeNull()
        // Nothing is sold online for this night, so there is no ticket link.
        event.ticketUrl.shouldBeNull()
        event.soldOut shouldBe false
    }

    @Test
    fun `falls back to the JSON-LD name when the page renders no heading`() {
        val event =
            parseHtml(
                """
                <html><body><script type="application/ld+json">[{
                  "@type": "MusicEvent",
                  "name": "Halloween in der Kulturbrauerei - Samstag - 31. Oktober 2026 - Soda Club Berlin",
                  "startDate": "2026-10-31T22:00:00+01:00"
                }]</script></body></html>
                """.trimIndent()
            ).shouldNotBeNull()

        event.title shouldBe "Halloween in der Kulturbrauerei - Samstag"
        event.eventDate shouldBe LocalDate.of(2026, 10, 31)
    }

    @Test
    fun `reads the sold-out flag and the cancelled status from the JSON-LD offer`() {
        val event =
            parseHtml(
                """
                <html><body><h1 class="title">Sodalicious</h1>
                <script type="application/ld+json">[{
                  "@type": "MusicEvent",
                  "name": "Sodalicious",
                  "startDate": "2026-08-01T22:00:00+02:00",
                  "eventStatus": "https://schema.org/EventCancelled",
                  "offers": [{"price": "12.00", "availability": " https://schema.org/SoldOut "}]
                }]</script></body></html>
                """.trimIndent()
            ).shouldNotBeNull()

        event.soldOut shouldBe true
        event.status shouldBe "CANCELLED"
        event.pricePresale shouldBe BigDecimal("12.00")
    }

    @Test
    fun `returns null for a page without an event title`() {
        parseHtml("<html><body></body></html>") shouldBe null
    }
}
