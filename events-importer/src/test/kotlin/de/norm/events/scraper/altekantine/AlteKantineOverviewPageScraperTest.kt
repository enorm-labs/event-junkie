package de.norm.events.scraper.altekantine

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [AlteKantineOverviewPageScraper].
 *
 * Uses a real homepage snapshot (10 Content Views items) and a fixed clock
 * (2026-07-01, before the snapshot's earliest date 13.07.) so the year inferred
 * from the year-less `DD.MM.` dates is deterministic. The overview carries no kind
 * label, so its events classify from the title only (a party/quiz venue → QUIZ or
 * OTHER, never a headliner concert), and no artists are extracted for the OTHER
 * events — the detail page's `Was:` / `DJ:` fields supply both.
 */
class AlteKantineOverviewPageScraperTest {
    private val baseUrl = "https://alte-kantine.eu/"

    // Pin the clock before the snapshot's earliest date (13.07.2026) so every event is upcoming
    // and the year inferred from the year-less "DD.MM." dates (nearest occurrence) is deterministic.
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-01T10:00:00Z"), ZoneOffset.UTC)
    private val scraper = AlteKantineOverviewPageScraper(clock)
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/altekantine/altekantine-overview.html")!!
                .bufferedReader()
                .readText()
        events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(postId: String): ScrapedEvent = events.first { it.sourceId == "alte_kantine:$postId" }

    @Test
    fun `extracts every event item from the fixture`() {
        events shouldHaveSize 10
    }

    @Test
    fun `parses title, act line, date, start time and detail URL for a party night`() {
        val edn = event("12370")
        edn.title shouldBe "Everybody Dance Now!"
        edn.subtitle shouldBe "Funky Henning"
        // "16.07." with no year → 2026 (nearest occurrence to the fixed clock).
        edn.eventDate shouldBe LocalDate.of(2026, 7, 16)
        edn.startTime shouldBe LocalTime.of(22, 0)
        edn.sourceUrl shouldBe "https://alte-kantine.eu/?p=12370"
        // The overview has no kind label; the title carries no party keyword, so it stays OTHER
        // until the detail page's "Was: Party" promotes it — hence no artists here either.
        edn.eventType shouldBe EventType.OTHER.name
        edn.artists.shouldBeEmpty()
    }

    @Test
    fun `classifies a quiz night from its title keyword`() {
        val quiz = event("12281")
        quiz.title shouldBe "Quiz Night Show"
        quiz.subtitle shouldBe "Pubquiz"
        quiz.eventType shouldBe EventType.QUIZ.name
        quiz.eventDate shouldBe LocalDate.of(2026, 7, 17)
        quiz.artists.shouldBeEmpty()
    }

    @Test
    fun `leaves the subtitle null when the act line is empty`() {
        val beerPong = event("12349")
        beerPong.title shouldBe "Beer Pong Night"
        beerPong.subtitle.shouldBeNull()
        beerPong.startTime shouldBe LocalTime.of(20, 30)
    }

    @Test
    fun `infers the current year for every listed date`() {
        events.map { it.eventDate.year }.toSet() shouldBe setOf(2026)
    }

    @Test
    fun `types the label-less Kantinenlesen as a reading from its title`() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/altekantine/altekantine-overview-page-2.html")!!
                .bufferedReader()
                .readText()
        val lesen = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single { it.sourceId == "alte_kantine:12466" }

        lesen.title shouldBe "Kantinenlesen"
        lesen.eventType shouldBe EventType.READING.name
    }
}
