package de.norm.events.scraper.mehringhof

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
        val event = scraper.scrape(fixtureDocument("mehringhof/mehringhof-ticket.html", url), url).shouldNotBeNull()

        event.description.shouldNotBeNull() shouldStartWith "LIVE Stand Up Comedy im Mehringhof-Theater!"
        event.imageUrl.shouldNotBeNull() shouldStartWith "https://tickets.mehringhoftheater.de/uploads/"
        event.pricePresale shouldBe BigDecimal("24.9")
        event.startTime shouldBe LocalTime.of(20, 0)
        event.soldOut shouldBe false
        event.endTime.shouldBeNull()
    }

    @Test
    fun `drops the shop's concessions note and underscore rule from the description (#5098)`() {
        val concessionsUrl = "https://tickets.mehringhoftheater.de/produkte/97180-tickets-fun-facts-mit-linus-neumann-mehringhof-theater-berlin-am-17-11-2026"
        val event = scraper.scrape(fixtureDocument("mehringhof/mehringhof-ticket-concessions.html", concessionsUrl), concessionsUrl).shouldNotBeNull()

        event.description.shouldNotBeNull().run {
            this shouldStartWith "An diesem Abend präsentieren Linus Neumann"
            this shouldNotContain "ermäßigte Ticketpreis"
            this shouldNotContain "____"
        }
    }

    @Test
    fun `keeps a description that has only the show's text`() {
        "Erster Absatz.\n\nZweiter Absatz.".withoutShopNotes() shouldBe "Erster Absatz.\n\nZweiter Absatz."
        "Der ermäßigte Ticketpreis gilt für Schüler.\n\n_____".withoutShopNotes().shouldBeNull()
    }

    @Test
    fun `returns null for a page without JSON-LD`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", url), url).shouldBeNull()
    }
}
