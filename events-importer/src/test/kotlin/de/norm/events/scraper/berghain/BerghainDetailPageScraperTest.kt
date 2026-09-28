package de.norm.events.scraper.berghain

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
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
        // No running order published yet: no set times, and the overview's lineup stands alone.
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

    @Test
    fun `reads the running order's set times per floor, once the venue has published them`() {
        val url = "https://www.berghain.berlin/de/event/80744/"
        val event = scraper.scrape(Jsoup.parse(loadFixture("scraper/berghain/berghain-detail-running-order.html"), url), url)!!

        // 15 slots on three floors; one of them a back-to-back of two DJs.
        event.artists shouldHaveSize 16
        event.artists.map { it.stage }.distinct() shouldBe listOf("Berghain", "Panorama Bar", "Garten")

        val opener = event.artists.first()
        opener.name shouldBe "Joline Scheffler"
        opener.setStart shouldBe Instant.parse("2026-09-26T21:59:00Z")
        opener.setEnd shouldBe Instant.parse("2026-09-27T02:30:00Z")

        // The `Live` marker and the label span nested in the name span are not part of the name.
        event.artists.first { it.setStart == Instant.parse("2026-09-27T02:30:00Z") && it.stage == "Berghain" }.name shouldBe "Colin Benders"

        val b2b = event.artists.filter { it.setStart == Instant.parse("2026-09-27T23:00:00Z") }
        b2b.map { it.name } shouldBe listOf("nd_baumecker", "Jorkes")
        b2b.map { it.setEnd }.distinct() shouldBe listOf(Instant.parse("2026-09-28T03:00:00Z"))
    }

    @Test
    fun `splits an upper-case B2B slot and keeps each act's own set`() {
        val url = "https://www.berghain.berlin/de/event/80845/"
        val event = scraper.scrape(Jsoup.parse(loadFixture("scraper/berghain/berghain-detail-running-order-b2b.html"), url), url)!!

        event.artists.map { it.name } shouldBe listOf("KĀ", "Egregore", "Jolly", "Ninon", "Agata", "Cunt Remember")
        event.artists.map { it.stage }.distinct() shouldBe listOf("Säule")
        event.artists.first { it.name == "Jolly" }.setStart shouldBe Instant.parse("2026-09-24T21:30:00Z")
    }
}
