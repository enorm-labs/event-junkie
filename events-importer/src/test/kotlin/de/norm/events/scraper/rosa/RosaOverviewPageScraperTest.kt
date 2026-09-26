package de.norm.events.scraper.rosa

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [RosaOverviewPageScraper], against a saved copy of the real `/dates` page.
 *
 * Two fixtures, because the site has two states: `rosa-overview.html` is the page a request with
 * the age-gate cookie gets, and `rosa-age-gate.html` is the page a request without one gets. The
 * second is what silently imports nothing if the cookie ever stops being sent.
 */
class RosaOverviewPageScraperTest {
    private val scraper = RosaOverviewPageScraper()
    private val baseUrl = "https://www.rosaclub.de/dates"

    private fun fixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("scraper/rosa/$name")!!
            .bufferedReader()
            .readText()

    private fun scrape(name: String) = scraper.scrape(Jsoup.parse(fixture(name), baseUrl), baseUrl)

    @Test
    fun `tags every night Techno, the club's sound, since it names no style`() {
        scrape("rosa-overview.html").map { it.genre }.distinct() shouldBe listOf("Techno")
    }

    @Test
    fun `scrape reads every event from the flight payload`() {
        val events = scrape("rosa-overview.html")

        events shouldHaveSize 2
        events.map { it.title } shouldBe listOf("5 YEARS LIBIDOH", "PORNCEPTUAL")
    }

    @Test
    fun `scrape maps a fully populated event`() {
        val event = scrape("rosa-overview.html").first()

        event.title shouldBe "5 YEARS LIBIDOH"
        event.eventDate shouldBe LocalDate.of(2026, 9, 19)
        // The payload states an opening range ("23:00 – 08:00"); the start is its first clock.
        event.startTime shouldBe LocalTime.of(23, 0)
        event.description shouldBe "5 years LIBIDOH"
        event.eventType shouldBe "PARTY"
        event.ticketUrl shouldBe "https://de.ra.co/events/2467907"
        // A Sanity asset reference is a URL only after its dimensions and extension are split out.
        event.imageUrl shouldBe
            "https://cdn.sanity.io/images/m0p64e3g/production/" +
            "1e1dc82d845bf64549d0f6b17c6c598118c9698e-1080x1350.png"
        // The programme is one page, so the anchor is what makes a row point at its own night.
        event.sourceUrl shouldBe "https://www.rosaclub.de/dates#event-gDBdXhZy09MfZRneVasuNW"
        event.sourceId shouldBe "rosa:gDBdXhZy09MfZRneVasuNW"
    }

    @Test
    fun `scrape drops a TBA description rather than storing the word`() {
        val event = scrape("rosa-overview.html")[1]

        event.title shouldBe "PORNCEPTUAL"
        event.description.shouldBeNull()
        event.imageUrl shouldBe
            "https://cdn.sanity.io/images/m0p64e3g/production/" +
            "372087c7cb47510758387c5a5ce98ff7510760ef-960x1280.jpg"
    }

    @Test
    fun `scrape finds nothing on the age gate, which is the page without the cookie`() {
        scrape("rosa-age-gate.html").shouldBeEmpty()
    }

    @Test
    fun `scrape survives a page with no flight payload at all`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Soon</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
