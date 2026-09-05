package de.norm.events.scraper.clubost

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month

/**
 * Unit tests for [ClubOstOverviewPageScraper], parsing a snapshot of the real Club OST
 * homepage taken on 5 August 2026.
 */
class ClubOstOverviewPageScraperTest {
    private val scraper = ClubOstOverviewPageScraper()
    private val sourceUrl = "https://clubost.de/"

    private fun scrapeFixture() =
        scraper.scrape(
            Jsoup.parse(loadFixture("scraper/clubost/clubost-overview.html"), sourceUrl),
            sourceUrl
        )

    private fun loadFixture(path: String): String =
        javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    @Test
    fun `scrape extracts every card on the homepage`() {
        scrapeFixture() shouldHaveSize 8
    }

    @Test
    fun `scrape maps a fully populated card`() {
        val event = scrapeFixture().first { it.sourceId == "club_ost:231438" }

        // The listing template upper-cases the title; the detail page supplies the real casing.
        event.title shouldBe "BLASPHEMY"
        event.eventDate shouldBe LocalDate.of(2026, 8, 7)
        event.startTime shouldBe LocalTime.of(23, 0)
        event.eventType shouldBe "PARTY"
        event.sourceUrl shouldBe "https://clubost.de/event/231438/"
        event.ticketUrl shouldBe "https://de.ra.co/events/2391028"
        event.imageUrl.shouldNotBeNull().startsWith("https://bookmetender-club-production-event-attachments") shouldBe true
    }

    @Test
    fun `scrape reads the ticket link out of the anchor nested inside the card link`() {
        // The card's markup nests the Resident Advisor anchor inside the anchor wrapping the
        // whole card. Jsoup's HTML5 adoption-agency handling splits those into siblings, so
        // this asserts the ticket link survives that rewrite for every card rather than the
        // card's own /event/ href being picked up by mistake.
        val ticketUrls = scrapeFixture().map { it.ticketUrl }
        ticketUrls.filterNotNull() shouldHaveSize 8
        ticketUrls.filterNotNull().all { it.startsWith("https://de.ra.co/events/") } shouldBe true
    }

    @Test
    fun `scrape treats the house logo placeholder as no image`() {
        // Cards with no flyer uploaded fall back to /static/images/logos/logo_long.png, a
        // site-relative path — it must not be stored as the event's image.
        val withoutFlyer = scrapeFixture().first { it.sourceId == "club_ost:233300" }

        withoutFlyer.title shouldBe "RAVE THE PLANET TRUCK"
        withoutFlyer.imageUrl shouldBe null
    }

    @Test
    fun `scrape parses a start time carrying minutes`() {
        // Django prints minutes only when they are non-zero, so the New Year's Eve door time
        // is the one card exercising the "11:55 p.m." shape.
        val newYear = scrapeFixture().first { it.sourceId == "club_ost:239128" }

        newYear.eventDate shouldBe LocalDate.of(2026, 12, 31)
        newYear.startTime shouldBe LocalTime.of(23, 55)
    }

    @Test
    fun `scrape parses an afternoon start time`() {
        val daytime = scrapeFixture().first { it.sourceId == "club_ost:234028" }

        daytime.eventDate shouldBe LocalDate.of(2026, 8, 30)
        daytime.startTime shouldBe LocalTime.of(14, 0)
    }

    @Test
    fun `scrape covers the full announced season in one fetch`() {
        // The homepage is the whole programme — no pagination, no month pages — so a single
        // parse must reach December from August.
        val dates = scrapeFixture().map { it.eventDate }.sorted()

        dates.first() shouldBe LocalDate.of(2026, 8, 7)
        dates.last() shouldBe LocalDate.of(2026, 12, 31)
    }

    @Test
    fun `scrape derives sourceId from the stable event id`() {
        scrapeFixture().map { it.sourceId }.sorted() shouldContainExactly
            listOf(
                "club_ost:215982",
                "club_ost:218692",
                "club_ost:226689",
                "club_ost:228340",
                "club_ost:231438",
                "club_ost:233300",
                "club_ost:234028",
                "club_ost:239128"
            )
    }

    @Test
    fun `scrape publishes no genre, price, lineup or doors time`() {
        // None of these are on the site at all; asserting it keeps a later "fix" from
        // inventing them.
        scrapeFixture().forEach { event ->
            event.genre shouldBe null
            event.doorsTime shouldBe null
            event.pricePresale shouldBe null
            event.priceBoxOffice shouldBe null
            event.priceNote shouldBe null
            event.artists.shouldBeEmpty()
            event.promoters.shouldBeEmpty()
            event.free shouldBe false
            event.soldOut shouldBe false
            event.status shouldBe "SCHEDULED"
        }
    }

    @Test
    fun `scrape skips a card with no date and keeps the rest`() {
        val html =
            """
            <html><body>
              <div class="event-item">
                <a href="/event/111/"><div class="event-flyer"></div></a>
                <div class="event-description">
                  <h3>NO DATE</h3>
                  <div class="event-info"><span>Coming soon</span></div>
                </div>
              </div>
              <div class="event-item">
                <a href="/event/222/"><div class="event-flyer"></div></a>
                <div class="event-description">
                  <h3>REAL ONE</h3>
                  <div class="event-info"><span>Oct. 4, 2026 | 11 p.m.</span></div>
                </div>
              </div>
            </body></html>
            """.trimIndent()

        val events = scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)

        events shouldHaveSize 1
        events.first().sourceId shouldBe "club_ost:222"
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 4)
    }

    @Test
    fun `scrape keeps every card once the detail links carry UUIDs`() {
        // Homepage of 5 September 2026: ten September nights, `/event/<uuid>/` links. The numeric
        // pattern dropped two of the ten and merged others onto one id (#1131).
        val events =
            scraper.scrape(
                Jsoup.parse(loadFixture("scraper/clubost/clubost-overview-uuid.html"), sourceUrl),
                sourceUrl
            )

        val september = events.filter { it.eventDate.month == Month.SEPTEMBER }
        september.map { it.eventDate.dayOfMonth }.sorted() shouldContainExactly listOf(5, 10, 11, 12, 17, 18, 19, 24, 25, 26)
        events.map { it.sourceId }.toSet().size shouldBe events.size
        events.first { it.eventDate == LocalDate.of(2026, 9, 5) }.sourceId shouldBe "club_ost:e9bdde1e-299a-4cc3-ad01-c8d3011aa869"
        events.first { it.eventDate == LocalDate.of(2026, 9, 5) }.sourceUrl shouldBe
            "https://clubost.de/event/e9bdde1e-299a-4cc3-ad01-c8d3011aa869/"
    }

    @Test
    fun `scrape skips a card whose link carries no event id`() {
        val html =
            """
            <html><body>
              <div class="event-item">
                <a href="/event/teaser/"><div class="event-flyer"></div></a>
                <div class="event-description">
                  <h3>TEASER</h3>
                  <div class="event-info"><span>Oct. 4, 2026 | 11 p.m.</span></div>
                </div>
              </div>
            </body></html>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl).shouldBeEmpty()
    }

    @Test
    fun `scrape returns an empty list for a page with no cards`() {
        val html = "<html><body><div class='event-list'></div></body></html>"

        scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl).shouldBeEmpty()
    }
}
