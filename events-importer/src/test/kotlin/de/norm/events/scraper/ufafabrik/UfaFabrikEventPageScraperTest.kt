package de.norm.events.scraper.ufafabrik

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** Unit tests for [UfaFabrikEventPageScraper]: the blurb, and a ticket link written into it. */
class UfaFabrikEventPageScraperTest {
    private val scraper = UfaFabrikEventPageScraper()
    private val url = "https://ufafabrik.de/veranstaltung/40877/ahmet-bozkus"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/ufafabrik/$name")!!
            .bufferedReader()
            .readText()

    private fun scrapeBody(body: String): ScrapedEvent? =
        scraper.scrape(Jsoup.parse("""<article><div class="field--name-body">$body</div></article>""", url), url)

    @Test
    fun `a Ticketlink line becomes the ticket link and leaves the blurb`() {
        val event = scraper.scrape(Jsoup.parse(fixture("ufafabrik-detail-ahmet-bozkus.html"), url), url)!!
        event.ticketUrl shouldBe "https://www.tickettailor.com/events/dwjmm/2315670"
        val description = event.description.orEmpty()
        description shouldNotContain "Ticketlink"
        description shouldEndWith "Instagram: https://www.instagram.com/ahmetbozkus"
    }

    @Test
    fun `the anchor after the label wins over its text, which stays in the blurb`() {
        val event = scrapeBody("""<p>Ein Abend.</p><p>Ticketlink: <a href="https://shop.example/tickets/1">hier</a></p>""")!!
        event.ticketUrl shouldBe "https://shop.example/tickets/1"
        event.description shouldBe "Ein Abend.\nTicketlink: hier"
    }

    @Test
    fun `a label in bold before the anchor counts`() {
        val event = scrapeBody("""<p>Ein Abend.</p><p><strong>Zum Ticket-Vorverkauf:</strong><a href="https://t.example/3"> https://t.example/3</a></p>""")!!
        event.ticketUrl shouldBe "https://t.example/3"
        event.description shouldBe "Ein Abend."
    }

    @Test
    fun `a ticket line without a URL stays in the blurb and sets no link`() {
        val event = scrapeBody("""<p>Ein Abend.<br>Tickets: 20 Euro</p>""")!!
        event.ticketUrl.shouldBeNull()
        event.description shouldBe "Ein Abend.\nTickets: 20 Euro"
    }

    @Test
    fun `a URL written as plain text is read from the line`() {
        val event = scrapeBody("""<p>Ein Abend.<br>Ticket-Link: https://shop.example/tickets/2</p>""")!!
        event.ticketUrl shouldBe "https://shop.example/tickets/2"
        event.description shouldBe "Ein Abend."
    }

    @Test
    fun `a show without the line has no ticket link`() {
        val event = scraper.scrape(Jsoup.parse(fixture("ufafabrik-detail-elsa.html"), url), url)!!
        event.ticketUrl.shouldBeNull()
    }

    @Test
    fun `a page with neither blurb nor ticket line yields nothing`() {
        scrapeBody("<p>Foto: Jane Doe</p>").shouldBeNull()
    }
}
