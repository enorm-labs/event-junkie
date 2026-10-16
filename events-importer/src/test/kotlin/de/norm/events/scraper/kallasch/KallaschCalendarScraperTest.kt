package de.norm.events.scraper.kallasch

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Unit tests for [KallaschCalendarScraper], against a saved copy of the real `?mec-ical-feed=1` feed. */
class KallaschCalendarScraperTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = KallaschCalendarScraper(clock)

    private val events: List<ScrapedEvent> by lazy {
        scraper.scrape(
            javaClass.classLoader
                .getResourceAsStream("scraper/kallasch/kallasch-events.ics")!!
                .bufferedReader()
                .readText()
        )
    }

    private fun series(title: String) = events.filter { it.title == title }

    @Test
    fun `scrape reads the one-offs and expands every series over the horizon`() {
        events.size shouldBe 28
        events.minOf { it.eventDate } shouldBe LocalDate.of(2026, 10, 17)
        events.maxOf { it.eventDate } shouldBe LocalDate.of(2027, 4, 9)
        events.map { it.sourceId }.distinct().size shouldBe events.size
    }

    @Test
    fun `scrape maps a one-off concert with its poster, times, free entry and title act`() {
        val event = events.single { it.title == "Mo Klé" }

        event.eventType shouldBe "CONCERT"
        event.eventDate shouldBe LocalDate.of(2026, 10, 24)
        event.startTime shouldBe LocalTime.of(20, 30)
        event.endDate shouldBe LocalDate.of(2026, 10, 24)
        event.endTime shouldBe LocalTime.of(22, 0)
        event.sourceUrl shouldBe "https://kallasch.berlin/programm/konzert-11-884-335-104-264-632-636-864-495/"
        event.sourceId shouldBe "kallasch:konzert-11-884-335-104-264-632-636-864-495"
        event.description!! shouldContain "Three Chords and a Shaking Hand"
        event.free shouldBe true
        event.artists.map { it.name } shouldContainExactly listOf("Mo Klé")
    }

    @Test
    fun `scrape decodes the entities in a title`() {
        events.single { it.title == "Frankie & Yamirah" }.eventDate shouldBe LocalDate.of(2026, 10, 31)
    }

    @Test
    fun `scrape expands a monthly rule from its next date and skips its excluded dates`() {
        val openMic = series("OPEN MIC // Freiraum&")

        openMic.first().eventType shouldBe "OTHER"
        openMic.map { it.eventDate } shouldContainExactly
            listOf(
                LocalDate.of(2026, 10, 29),
                LocalDate.of(2026, 11, 26),
                LocalDate.of(2026, 12, 31),
                LocalDate.of(2027, 1, 28),
                LocalDate.of(2027, 2, 25),
                LocalDate.of(2027, 3, 25)
            )
        openMic.first().sourceId shouldBe "kallasch:open-mic-freiraum-532-2026-10-29"
        openMic.first().sourceUrl shouldBe "https://kallasch.berlin/programm/open-mic-freiraum-532/?occurrence=2026-10-29"
        openMic.first().artists.shouldBeEmpty()
    }

    @Test
    fun `scrape types a quiz and a slam by their category and drops the prefix`() {
        series("Musikquiz Moabit").map { it.eventDate }.take(2) shouldContainExactly listOf(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 12, 3))
        series("Musikquiz Moabit").first().eventType shouldBe "QUIZ"
        series("Musikquiz Moabit").first().free shouldBe true
        series("Eagel Slam 1000").first().eventType shouldBe "READING"
        series("Eagel Slam 1000").first().startTime shouldBe LocalTime.of(19, 30)
    }

    @Test
    fun `scrape reads a date list from today on, at the series' own time`() {
        val jazz = series("Live Jazz @ Kallasch&")

        jazz.map { it.eventDate } shouldContainExactly
            listOf(
                LocalDate.of(2026, 10, 18),
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 12, 6),
                LocalDate.of(2026, 12, 20)
            )
        jazz.map { it.startTime }.distinct() shouldContainExactly listOf(LocalTime.of(20, 30))
        jazz.first().endTime.shouldBeNull()
        jazz.first().artists.shouldBeEmpty()
    }

    @Test
    fun `scrape keeps the start date of a rule it cannot expand`() {
        val calendar =
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Berlin:20261105T200000
            RRULE:FREQ=MONTHLY;BYSETPOS=2;BYDAY=TH
            SUMMARY:QUIZ // Test
            URL:https://kallasch.berlin/programm/test/
            CATEGORIES:QUIZ
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()

        scraper.scrape(calendar).map { it.eventDate } shouldContainExactly listOf(LocalDate.of(2026, 11, 5))
    }

    @Test
    fun `scrape skips an entry without a URL`() {
        val calendar = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART;TZID=Europe/Berlin:20261105T200000\nSUMMARY:KONZERT // X\nEND:VEVENT\nEND:VCALENDAR\n"

        scraper.scrape(calendar).shouldBeEmpty()
    }
}
