package de.norm.events.scraper.zitaclub

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Unit tests for [ZitaClubEventPageScraper], against saved event pages. */
class ZitaClubEventPageScraperTest {
    private val scraper = ZitaClubEventPageScraper()

    private fun page(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/zitaclub/$name")!!
                .bufferedReader()
                .readText(),
            "https://zita-club.de/events/"
        )

    private fun row(slug: String) =
        ScrapedEvent(
            title = "Night",
            eventDate = LocalDate.of(2026, 10, 17),
            sourceUrl = "https://zita-club.de/events/$slug/",
            sourceId = "zita_club:$slug"
        )

    @Test
    fun `enrich reads the tags as the genre, the price and the ticket link`() {
        val event = scraper.enrich(row("rock-at-zita-16"), page("zitaclub-event-rock-at-zita.html")).shouldNotBeNull()

        event.genre shouldBe "Alternativ Rock, Classic Rock, Metal, Rock"
        event.pricePresale shouldBe BigDecimal("10.00")
        event.ticketUrl shouldBe "https://zitaclub.ticket.io/MacNDhpq/?"
    }

    @Test
    fun `a page without tags or price leaves those fields empty and keeps its ticket link`() {
        val event =
            scraper.enrich(row("danse-macabre-a-tribute-to-bruno-kramm"), page("zitaclub-event-danse-macabre.html")).shouldNotBeNull()

        event.genre.shouldBeNull()
        event.pricePresale.shouldBeNull()
        event.ticketUrl shouldBe "https://www.eventim-light.com/de/a/6abe6594dbaa6ba5680ed4a4/e/6abe6befdbaa6ba5680ed518"
    }

    @Test
    fun `a page without an event yields nothing`() {
        scraper.enrich(row("gone"), Jsoup.parse("<html><body><p>Seite nicht gefunden</p></body></html>")).shouldBeNull()
    }
}
