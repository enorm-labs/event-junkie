package de.norm.events.scraper.badehaus

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
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
}
