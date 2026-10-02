package de.norm.events.scraper.badehaus

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.withWeekdayWarnings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [BadehausOverviewPageScraper].
 *
 * Parses the real `/events/` listing snapshot and asserts card extraction plus the
 * CSS-class-based sold-out / relocated status signals.
 */
class BadehausOverviewPageScraperTest {
    private val scraper = BadehausOverviewPageScraper()
    private val baseUrl = "https://badehaus-berlin.com/events/"

    private fun listing() =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/badehaus/badehaus-events.html")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    private fun events() = scraper.scrape(listing(), baseUrl)

    @Test
    fun `parses every event card on the listing`() {
        events() shouldHaveSize 90
    }

    @Test
    fun `extracts all fields of a scheduled event`() {
        val ela = events().first { it.sourceId == "badehaus:ela" }
        ela.title shouldBe "ela."
        ela.eventDate shouldBe LocalDate.of(2026, 9, 23)
        ela.doorsTime shouldBe LocalTime.of(19, 0)
        ela.sourceUrl shouldBe "https://badehaus-berlin.com/events/ela/"
        ela.subtitle shouldBe "Pinke Plüschjacke Tour | Deutsch-Pop"
        ela.ticketUrl.shouldNotBeNull()
        ela.ticketUrl shouldStartWith "https://www.eventim-light.com/"
        ela.imageUrl.shouldNotBeNull()
        ela.imageUrl shouldStartWith "https://badehaus-berlin.com/wp-content/uploads/"
        ela.soldOut shouldBe false
        ela.status shouldBe EventStatus.SCHEDULED.name
        // No category is published, so the type is inferred: a plain music event → CONCERT.
        ela.eventType shouldBe EventType.CONCERT.name
    }

    @Test
    fun `infers the event type from the title when no category is published`() {
        val events = events()
        events.first { it.sourceId == "badehaus:pubquiz-with-simply-quiz-162" }.eventType shouldBe EventType.QUIZ.name
        events.first { it.sourceId == "badehaus:call-me-maybe-2000s-2010s-pop-party-8" }.eventType shouldBe
            EventType.PARTY.name
        events.first { it.sourceId == "badehaus:world-cup-2026-live-screening-7" }.eventType shouldBe
            EventType.SCREENING.name
    }

    @Test
    fun `classifies a themed club night as a party, not a concert`() {
        // "Pop Girly Night" is a themed club night, not a live act — the "night" keyword
        // classifies it PARTY so its event-name title isn't minted as a fake artist.
        val night = events().first { it.sourceId == "badehaus:pop-girly-night-7" }
        night.eventType shouldBe EventType.PARTY.name
        night.artists.shouldBeEmpty()
    }

    @Test
    fun `extracts the concert title as the headliner artist`() {
        // Badehaus publishes no roster; for an inferred CONCERT the title is the act.
        val ela = events().first { it.sourceId == "badehaus:ela" }
        ela.eventType shouldBe EventType.CONCERT.name
        ela.artists shouldContainExactly listOf(ScrapedArtist(name = "ela.", role = "HEADLINER", titleDerived = true))
    }

    @Test
    fun `does not extract artists from non-concert events`() {
        val events = events()
        events.first { it.sourceId == "badehaus:pubquiz-with-simply-quiz-162" }.artists.shouldBeEmpty()
        events.first { it.sourceId == "badehaus:call-me-maybe-2000s-2010s-pop-party-8" }.artists.shouldBeEmpty()
        events.first { it.sourceId == "badehaus:world-cup-2026-live-screening-7" }.artists.shouldBeEmpty()
    }

    @Test
    fun `flags a sold-out event from the AUSVERKAUFT card class`() {
        val soldOut = events().first { it.sourceId == "badehaus:futurebae" }
        soldOut.soldOut shouldBe true
        // Sold out is a flag, not a status.
        soldOut.status shouldBe EventStatus.SCHEDULED.name
        soldOut.eventDate shouldBe LocalDate.of(2026, 9, 22)
    }

    @Test
    fun `maps a VERLEGT card class to RELOCATED status`() {
        val relocated = events().first { it.sourceId == "badehaus:forager" }
        relocated.status shouldBe EventStatus.RELOCATED.name
        relocated.soldOut shouldBe false
    }

    @Test
    fun `returns no events for a listing without cards`() {
        val html = "<html><body><div class='content'><p>No events</p></div></body></html>"
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl) shouldHaveSize 0
    }

    @Test
    fun `reads the genre from the subtitle line, skipping a tour name and the support part`() {
        // Six teaser lines from the listing on 2026-09-30.
        val lines =
            listOf(
                "Metalcore",
                "Alternative Rock",
                "Indie, Indie-Rock",
                "Trash Metal | Support: Belligerence / Hexed",
                "Licht am Horizont Tour | Rap",
                "Kein Plan B-Tour 2026 | Deutschrock",
                "EVERYBODY’S HOME NOBODY’S HAPPY WORLD TOUR | Rock, Alternative",
                "dicker Groove, glänzende Disco-Vibes, fette Bläsersätze und Beats"
            )
        val cards =
            lines.withIndex().joinToString("") { (i, line) ->
                """<div><div class="eventlistimg"></div><div class="nomargin"><h2><a href="/events/show-$i/">Show $i</a></h2>""" +
                    """<p class="eventinfo">Mi. 30.09.2026 | 19:00 UHR</p><p>$line</p></div></div>"""
            }
        val genres = scraper.scrape(Jsoup.parse("<html><body>$cards</body></html>", baseUrl), baseUrl).map { it.genre }

        genres shouldBe
            listOf("Metalcore", "Rock", "Indie", "Metal", "Hip Hop", "Rock", "Rock, Alternative", null)
    }

    @Test
    fun `moves a card whose weekday names the neighbouring month`() {
        // 3 September 2026 is a Thursday; the Saturday the heading names is 3 October.
        val card =
            """<div><div class="eventlistimg"></div><div class="nomargin"><h2><a href="/events/show/">Show</a></h2>""" +
                """<p class="eventinfo">Sa. 03.09.2026 | 19:00 UHR</p></div></div>"""
        val (events, warnings) = withWeekdayWarnings { scraper.scrape(Jsoup.parse("<html><body>$card</body></html>", baseUrl), baseUrl) }

        events.single().eventDate shouldBe LocalDate.of(2026, 10, 3)
        warnings shouldHaveSize 1
    }
}
