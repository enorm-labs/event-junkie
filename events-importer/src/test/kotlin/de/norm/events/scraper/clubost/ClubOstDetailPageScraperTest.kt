package de.norm.events.scraper.clubost

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [ClubOstDetailPageScraper], parsing snapshots of two real Club OST detail
 * pages taken on 5 August 2026.
 */
class ClubOstDetailPageScraperTest {
    private val scraper = ClubOstDetailPageScraper()

    private fun scrapeFixture(
        fixture: String,
        url: String
    ) = scraper.scrape(Jsoup.parse(loadFixture(fixture), url), url)

    private fun loadFixture(path: String): String =
        javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    @Test
    fun `scrape reads the title in the casing the venue typed`() {
        // The reason this page is fetched at all: the listing shouts "BLASPHEMY".
        val event =
            scrapeFixture(
                "scraper/clubost/clubost-detail-blasphemy.html",
                "https://clubost.de/event/231438/"
            )

        event.shouldNotBeNull()
        event.title shouldBe "Blasphemy"
        event.eventDate shouldBe LocalDate.of(2026, 8, 7)
        event.startTime shouldBe LocalTime.of(23, 0)
        event.eventType shouldBe "PARTY"
        event.sourceId shouldBe "club_ost:231438"
        event.sourceUrl shouldBe "https://clubost.de/event/231438/"
        event.ticketUrl shouldBe "https://de.ra.co/events/2391028"
    }

    @Test
    fun `scrape maps the placeholder description to null`() {
        val event =
            scrapeFixture(
                "scraper/clubost/clubost-detail-blasphemy.html",
                "https://clubost.de/event/231438/"
            )

        event.shouldNotBeNull()
        event.description shouldBe null
    }

    @Test
    fun `scrape parses a page with no flyer uploaded`() {
        val event =
            scrapeFixture(
                "scraper/clubost/clubost-detail-nye-no-logo.html",
                "https://clubost.de/event/239128/"
            )

        event.shouldNotBeNull()
        event.title shouldBe "GEGEN X PRNCPTL X OST NYE 33H"
        event.eventDate shouldBe LocalDate.of(2026, 12, 31)
        event.startTime shouldBe LocalTime.of(23, 55)
        event.imageUrl shouldBe null
    }

    @Test
    fun `scrape ignores the end time the page states`() {
        // The page says the Blasphemy night ends at 8 a.m. the next morning. ScrapedEvent has
        // no end-time field, and folding it into a same-day time would be wrong for every
        // night running past midnight — so it is dropped, not approximated.
        val event =
            scrapeFixture(
                "scraper/clubost/clubost-detail-blasphemy.html",
                "https://clubost.de/event/231438/"
            )

        event.shouldNotBeNull()
        event.doorsTime shouldBe null
        event.startTime shouldBe LocalTime.of(23, 0)
    }

    @Test
    fun `scrape reads the label rows by their label, not their order`() {
        val url = "https://clubost.de/event/999/"
        val html =
            """
            <html><body>
              <h1>Site header</h1>
              <div class="container">
                <a href="/" class="button-link back-button">Back to homepage</a>
                <h1>Reordered Night</h1>
                <p><strong>Description:</strong> A real blurb.</p>
                <p><strong>End time:</strong> 8 a.m.</p>
                <p><strong>Start time:</strong> 11 p.m.</p>
                <p><strong>Date:</strong> Oct. 4, 2026</p>
                <a href="https://de.ra.co/events/123" class="button-link ticket">Buy tickets</a>
              </div>
            </body></html>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, url), url)

        event.shouldNotBeNull()
        event.title shouldBe "Reordered Night"
        event.eventDate shouldBe LocalDate.of(2026, 10, 4)
        event.startTime shouldBe LocalTime.of(23, 0)
        event.description shouldBe "A real blurb."
        event.ticketUrl shouldBe "https://de.ra.co/events/123"
    }

    @Test
    fun `scrape returns null when the page has no content container`() {
        val url = "https://clubost.de/event/999/"
        val html = "<html><body><h1>404</h1><p>Not found</p></body></html>"

        scraper.scrape(Jsoup.parse(html, url), url) shouldBe null
    }

    @Test
    fun `scrape returns null when the page states no parseable date`() {
        val url = "https://clubost.de/event/999/"
        val html =
            """
            <html><body><div class="container">
              <a href="/" class="button-link back-button">Back to homepage</a>
              <h1>Undated Night</h1>
              <p><strong>Date:</strong> to be announced</p>
            </div></body></html>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, url), url) shouldBe null
    }

    @Test
    fun `scrape returns null when the URL carries no event id`() {
        val url = "https://clubost.de/event/teaser/"
        val html =
            """
            <html><body><div class="container">
              <a href="/" class="button-link back-button">Back to homepage</a>
              <h1>Teaser</h1>
              <p><strong>Date:</strong> Oct. 4, 2026</p>
            </div></body></html>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, url), url) shouldBe null
    }
}
