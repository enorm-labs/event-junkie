package de.norm.events.scraper.berghain

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [BerghainDetailPageScraper], parsing saved event-detail snapshots:
 * a fully-populated club night (image, both prices, ticket link, description) and a
 * presale-sold-out night whose box office is still open.
 */
class BerghainDetailPageScraperTest {
    private companion object {
        const val URL = "https://www.berghain.berlin/de/event/82639/"
    }

    private val scraper = BerghainDetailPageScraper()

    private fun loadFixture(path: String): String =
        javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    @Test
    fun `parses a fully-populated detail page`() {
        val url = "https://www.berghain.berlin/de/event/80835/"
        val event = scraper.scrape(Jsoup.parse(loadFixture("scraper/berghain/berghain-detail-full.html"), url), url)!!

        event.title shouldBe "BUTOH Batorū"
        event.eventDate shouldBe LocalDate.of(2026, 7, 16)
        event.doorsTime.shouldBeNull()
        event.startTime shouldBe LocalTime.of(21, 0)
        event.eventType shouldBe EventType.PARTY.name
        event.genre shouldBe "Techno"
        event.imageUrl!!.shouldStartWith("https://cdn.berghain.berlin/media/images/")
        event.pricePresale shouldBe BigDecimal("20.00")
        event.priceBoxOffice shouldBe BigDecimal("22.00")
        event.ticketUrl shouldBe "https://ticketingv2.berghain.de/event/butoh"
        event.soldOut shouldBe false
        event.sourceId shouldBe "berghain:80835"
        event.description!!.shouldContain("Butoh Batorū")
        // The detail page never parses the lineup — the overview is authoritative.
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `treats a presale-sold-out night with an open box office as not sold out`() {
        val url = "https://www.berghain.berlin/de/event/80784/"
        val event =
            scraper.scrape(Jsoup.parse(loadFixture("scraper/berghain/berghain-detail-presale-soldout.html"), url), url)!!

        event.title shouldBe "Sound Metaphors"
        event.eventDate shouldBe LocalDate.of(2026, 7, 17)
        event.startTime shouldBe LocalTime.of(22, 0)
        event.eventType shouldBe EventType.PARTY.name
        // A two-floor night joins both rooms' genres, in the detail page's floor order.
        event.genre shouldBe "House, Techno"
        // "Vorverkauf ausverkauft" → no presale price, but the box office is still available.
        event.pricePresale.shouldBeNull()
        event.priceBoxOffice shouldBe BigDecimal("30.00")
        event.ticketUrl.shouldBeNull()
        event.soldOut shouldBe false
        event.sourceId shouldBe "berghain:80784"
    }

    // The Kantine writes a cancellation into the heading; the persistence boundary reads it (#1493).
    @Test
    fun `stores a heading that ends in Abgesagt as a cancelled event under the bare name`() {
        val url = "https://www.berghain.berlin/de/event/82554/"
        val event = scraper.scrape(Jsoup.parse(loadFixture("scraper/berghain/berghain-detail-cancelled.html"), url), url)!!

        event.title shouldBe "Olga Myko - Abgesagt"
        event.eventDate shouldBe LocalDate.of(2026, 9, 16)
        val entity = event.toEventEntity(venueId = 1, venueSlug = "kantine-am-berghain", eventSourceId = 1)
        entity.status shouldBe "CANCELLED"
        entity.title shouldBe "Olga Myko"
        entity.slug shouldBe "2026-09-16-kantine-am-berghain-olga-myko"
    }

    // The CMS prints a zero for a door price nobody set (#1589).
    @Test
    fun `drops a zero door price beside a paid presale instead of calling the night free`() {
        val event = scraper.scrape(Jsoup.parse(ticketPage("27,15€ via <a href=\"https://www.eventim.de/x\">Eventim</a>", "0,00€ Abendkasse"), URL), URL)!!

        event.pricePresale shouldBe BigDecimal("27.15")
        event.priceBoxOffice.shouldBeNull()
        event.ticketUrl shouldBe "https://www.eventim.de/x"
        event.toEventEntity(venueId = 1, venueSlug = "kantine-am-berghain", eventSourceId = 1).free shouldBe false
    }

    @Test
    fun `keeps a zero door price as a free night when no paid presale stands beside it`() {
        val event = scraper.scrape(Jsoup.parse(ticketPage("0,00€ Abendkasse"), URL), URL)!!

        event.pricePresale.shouldBeNull()
        event.priceBoxOffice shouldBe BigDecimal("0.00")
        event.toEventEntity(venueId = 1, venueSlug = "kantine-am-berghain", eventSourceId = 1).free shouldBe true
    }

    /** A minimal detail page in the venue's markup, with one `<p>` per ticket line. */
    private fun ticketPage(vararg lines: String): String =
        """
        <html><body><main>
          <h1>The Paris Match</h1>
          <p><span class="font-bold">03.11.2026</span> Beginn 20:00</p>
          <div class="mt-1"><h2>Tickets</h2>${lines.joinToString("") { "<p>$it</p>" }}</div>
        </main></body></html>
        """.trimIndent()

    @Test
    fun `returns null for a page without the main container`() {
        val url = "https://www.berghain.berlin/de/event/1/"
        scraper.scrape(Jsoup.parse("<html><body><p>no main</p></body></html>", url), url).shouldBeNull()
    }
}
