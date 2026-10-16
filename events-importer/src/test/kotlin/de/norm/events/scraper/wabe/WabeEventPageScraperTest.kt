package de.norm.events.scraper.wabe

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

class WabeEventPageScraperTest {
    private val url = "https://www.wabe-berlin.info/okt-2026/3/"
    private val listed =
        ScrapedEvent(title = "All These Feelings", eventDate = LocalDate.parse("2026-10-03"), sourceUrl = url, sourceId = "wabe:2026-10-03-all-these-feelings")

    @Test
    fun `reads the description and the ticket link`() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/wabe/wabe-event-okt-2026-3.html")!!
                .bufferedReader()
                .readText()

        val event = WabeEventPageScraper().scrape(listed, Jsoup.parse(html, url))!!

        event.ticketUrl shouldBe "https://shop.entrello.app/de/wabe/events/ev_3GRgDeriIgGTiIuYPZ5te0zDNb5/tickets"
        val description = event.description.shouldNotBeNull()
        description shouldStartWith "Am 3. Oktober kommt Laura Grandy zum dritten Mal in die WABE"
        description shouldNotContain "barrierefrei"
        description shouldNotContain "Kartenreservierungen"
    }

    @Test
    fun `drops the tracking parameter from a ticket link`() {
        val html =
            "<div id='content_area'><a class='j-calltoaction-link' href='https://shop.entrello.app/de/wabe/events/ev_1/tickets?_gl=1*abc'>Tickets online kaufen</a></div>"

        WabeEventPageScraper().scrape(listed, Jsoup.parse(html, url))?.ticketUrl shouldBe "https://shop.entrello.app/de/wabe/events/ev_1/tickets"
    }

    @Test
    fun `yields nothing from a page without prose or tickets`() {
        WabeEventPageScraper().scrape(listed, Jsoup.parse("<div id='content_area'><p>Infos folgen</p></div>", url)).shouldBeNull()
    }
}
