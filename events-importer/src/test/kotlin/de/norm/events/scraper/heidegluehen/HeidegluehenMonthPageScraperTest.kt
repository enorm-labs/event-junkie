package de.norm.events.scraper.heidegluehen

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [HeidegluehenMonthPageScraper].
 *
 * Parses a static snapshot of Heideglühen's `/monatsvorschau/` page for deterministic, offline-safe
 * testing without HTTP fetching.
 */
class HeidegluehenMonthPageScraperTest {
    private val scraper = HeidegluehenMonthPageScraper()
    private val sourceUrl = "https://heidegluehen.berlin/monatsvorschau/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/heidegluehen/heidegluehen-monatsvorschau.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)
    }

    private fun event(date: LocalDate): ScrapedEvent = events.first { it.eventDate == date }

    @Test
    fun `tags every night Techno, House, the club's sound, since the venue names no style`() {
        events.map { it.genre }.distinct() shouldBe listOf("Techno, House")
    }

    @Test
    fun `discovers the month's parties and nothing else on the page`() {
        // Five Saturdays. The "~~~" separators, the month's shared name-drop and the footer notes
        // all carry no date, which is what keeps them out.
        events shouldHaveSize 5
        events.map { it.eventDate } shouldBe
            listOf(1, 8, 15, 22, 29).map { LocalDate.of(2026, 8, it) }
    }

    @Test
    fun `maps a plain week, which reads date then name`() {
        val first = event(LocalDate.of(2026, 8, 1))
        first.title shouldBe "Heideglühen #26"
        first.subtitle.shouldBeNull()
        first.eventType shouldBe EventType.PARTY.name
        first.startTime shouldBe LocalTime.of(12, 0)
        first.sourceUrl shouldBe sourceUrl
        first.sourceId shouldBe "heidegluehen:2026-08-01"
    }

    @Test
    fun `maps the anniversary weekend, which reads name then date then name`() {
        // The venue puts the title above the date on this one, so lines are read by kind.
        val anniversary = event(LocalDate.of(2026, 8, 15))
        anniversary.title shouldBe "14 Jahre Heideglühen (Heideglühen #28)"
        anniversary.subtitle shouldBe "34-Stunden-Weekender"
        anniversary.startTime shouldBe LocalTime.of(12, 0)
    }

    @Test
    fun `stores the closing time on the day the tail names`() {
        // "(bis Sonntag, 6 Uhr)" from a Saturday noon: the next day (ADR-029). It used to be the description.
        val night = event(LocalDate.of(2026, 8, 1))
        night.endDate shouldBe LocalDate.of(2026, 8, 2)
        night.endTime shouldBe LocalTime.of(6, 0)
        night.description.shouldBeNull()

        val weekender = event(LocalDate.of(2026, 8, 15))
        weekender.endDate shouldBe LocalDate.of(2026, 8, 16)
        weekender.endTime shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `gives every party the month's artwork`() {
        // The page also carries the site logo and a close button; neither must win.
        events.forEach { it.imageUrl!! shouldEndWith "2026_Monatsvorschau_August.gif" }
    }

    @Test
    fun `identifies a party by its date, there being no per-event page`() {
        events.map { it.sourceId }.toSet() shouldHaveSize 5
        events.map { it.sourceUrl }.toSet() shouldHaveSize 1
        events.none { it.eventDate == UNRESOLVED_EVENT_DATE } shouldBe true
        events.none { it.startTime == null } shouldBe true
    }

    @Test
    fun `leaves the lineup to the week page`() {
        // The month page name-drops the whole month's DJs in one paragraph, which belongs to no
        // single date and must not be attached to any.
        events.forEach { it.artists.shouldBeEmpty() }
    }

    @Test
    fun `publishes no price and no ticket link, the party selling at the door`() {
        events.forEach {
            it.pricePresale.shouldBeNull()
            it.priceBoxOffice.shouldBeNull()
            it.priceNote.shouldBeNull()
            it.ticketUrl.shouldBeNull()
        }
    }

    @Test
    fun `parses the venue's prose date`() {
        val schedule = parseSchedule("Samstag, 1. August 2026, 12 Uhr (bis Sonntag, 6 Uhr)")!!
        schedule.date shouldBe LocalDate.of(2026, 8, 1)
        schedule.startTime shouldBe LocalTime.of(12, 0)
        schedule.endDate shouldBe LocalDate.of(2026, 8, 2)
        schedule.endTime shouldBe LocalTime.of(6, 0)
        // A closing weekday that is the start's own day stays on it.
        parseSchedule("Samstag, 1. August 2026, 12 Uhr (bis Samstag, 22 Uhr)")!!.endDate shouldBe LocalDate.of(2026, 8, 1)
        // The week page writes the same date without the parenthesis, and so without an end.
        val weekPage = parseSchedule("Samstag, 6. Juni 2026, 12 Uhr,")!!
        weekPage.date shouldBe LocalDate.of(2026, 6, 6)
        weekPage.endDate.shouldBeNull()
        weekPage.endTime.shouldBeNull()
        parseSchedule("Das Programm folgt am Dienstag…").shouldBeNull()
        parseSchedule("~~~").shouldBeNull()
    }

    @Test
    fun `returns an empty list for a page without a programme`() {
        val document = Jsoup.parse("<html><body><div class='fl-rich-text'><p>~~~</p></div></body></html>", sourceUrl)
        scraper.scrape(document, sourceUrl).shouldBeEmpty()
    }
}
