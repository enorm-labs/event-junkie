package de.norm.events.scraper.mehringhof

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime

class MehringhofTicketPageScraperTest {
    private val scraper = MehringhofTicketPageScraper()
    private val url = "https://tickets.mehringhoftheater.de/produkte/90764-tickets-comedy-flash-mehringhof-theater-berlin-am-03-10-2026"

    @Test
    fun `reads text, image, price and time from the JSON-LD`() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/mehringhof/mehringhof-ticket.html")!!
                .bufferedReader()
                .readText()
        val event = scraper.scrape(Jsoup.parse(html, url), url).shouldNotBeNull()

        event.description.shouldNotBeNull() shouldStartWith "LIVE Stand Up Comedy im Mehringhof-Theater!"
        event.imageUrl.shouldNotBeNull() shouldStartWith "https://tickets.mehringhoftheater.de/uploads/"
        event.pricePresale shouldBe BigDecimal("24.9")
        event.startTime shouldBe LocalTime.of(20, 0)
        event.soldOut shouldBe false
        event.endTime.shouldBeNull()
    }

    @Test
    fun `returns null for a page without JSON-LD`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", url), url).shouldBeNull()
    }
}
