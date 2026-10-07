package de.norm.events.scraper.urania

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [UraniaCalendarPageScraper].
 *
 * Parses a static snapshot of the Urania's `/kalender/` page for deterministic, offline-safe
 * testing without HTTP fetching.
 */
class UraniaCalendarPageScraperTest {
    private val scraper = UraniaCalendarPageScraper()
    private val baseUrl = "https://www.urania.de/kalender/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/urania/urania-kalender.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `discovers every event, including the two sharing one day`() {
        // 16 day blocks hold 17 events.
        events shouldHaveSize 17
        events.map { it.eventDate }.distinct() shouldHaveSize 16
    }

    @Test
    fun `maps a fully populated item`() {
        val opening = event("urania:demokratie-vor-der-wahl")
        opening.title shouldBe "Demokratie vor der Wahl"
        opening.subtitle shouldBe "bzw.:BEZIEHUNGSWESEN · Podium zur Spielzeiteröffnung"
        opening.eventDate shouldBe LocalDate.of(2026, 9, 3)
        opening.startTime shouldBe LocalTime.of(19, 30)
        opening.sourceUrl shouldBe "https://www.urania.de/event/demokratie-vor-der-wahl/"
        opening.ticketUrl!! shouldStartWith "https://uraniaberlin.reservix.de/"
    }

    @Test
    fun `reads the whole date out of the day's own token`() {
        // `data-day="03-do-09-2026"` states day, weekday, month and year in one attribute.
        event("urania:demokratie-vor-der-wahl").eventDate shouldBe LocalDate.of(2026, 9, 3)
        event("urania:how-context-modulates-memory-in-flies-and-humans").eventDate shouldBe LocalDate.of(2026, 11, 3)
        events.none { it.eventDate == UNRESOLVED_EVENT_DATE } shouldBe true
        events.none { it.startTime == null } shouldBe true
    }

    @Test
    fun `types the house's formats as spoken word`() {
        // The model has no lecture or panel type; READING is its spoken-word bucket.
        events.map { it.eventType }.toSet() shouldBe setOf(EventType.READING.name)
    }

    @Test
    fun `joins the programme strand and the format into the subtitle`() {
        event("urania:leibspeisen").subtitle shouldBe "LANGE LINIEN · Podiumsgespräch"
        // Neither is a musical genre, so neither is stored as one.
        events.none { it.genre != null } shouldBe true
    }

    @Test
    fun `stores the billed speakers as headliners`() {
        val opening = event("urania:demokratie-vor-der-wahl")
        opening.artists.map { it.name } shouldBe
            listOf(
                "Andrea Römmele",
                "Valerie Schönian",
                "Thorsten Faas",
                "Marcel Lewandowsky",
                "Johanna Sprondel",
                "Alexander Thiele"
            )
        opening.artists.map { it.role }.toSet() shouldBe setOf("HEADLINER")
    }

    @Test
    fun `drops the note the venue appends to a billing line`() {
        // "Hanna Notte, Steven Erlanger, Yoshua Yaffa - in englischer Sprache"
        val putin = event("urania:putins-global-campaign-to-defeat-the-west-en")
        putin.artists.map { it.name } shouldBe listOf("Hanna Notte", "Steven Erlanger", "Yoshua Yaffa")
    }

    @Test
    fun `drops the venue's stand-in for the panellists it did not name`() {
        // "Christoph Koch, Nikolaus Röttger et al."
        val feelings = event("urania:hallo-wie-fuehlst-du-dich-heute")
        feelings.artists.map { it.name } shouldBe listOf("Christoph Koch", "Nikolaus Röttger")
    }

    @Test
    fun `leaves the event-page-only fields empty`() {
        val opening = event("urania:demokratie-vor-der-wahl")
        opening.description.shouldBeNull()
        opening.imageUrl.shouldBeNull()
        opening.pricePresale.shouldBeNull()
        opening.priceNote.shouldBeNull()
        opening.free shouldBe false
    }

    @Test
    fun `every event links its own page and a ticket shop`() {
        events.map { it.sourceId }.toSet() shouldHaveSize 17
        events.none { it.ticketUrl == null } shouldBe true
        events.none { it.artists.isEmpty() } shouldBe true
    }

    @Test
    fun `returns an empty list for a page without a calendar`() {
        val document = Jsoup.parse("<html><body><div class='c-event-calendar'></div></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }

    @Test
    fun `skips a day whose token is not a date`() {
        val document =
            Jsoup.parse(
                """
                <html><body><div class="c-event-calendar_day js-day" data-day="soon">
                <div class="c-event-calendar-item"><h3 class="o-h3">Irgendwann</h3>
                <a class="c-event-calendar-item_content" href="https://www.urania.de/event/x/"></a></div>
                </div></body></html>
                """.trimIndent(),
                baseUrl
            )
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }

    @Test
    fun `drops a workshop or a guided walk, but keeps a quiz the venue files as a workshop`() {
        fun item(
            slug: String,
            title: String,
            format: String
        ) = """<div class="c-event-calendar-item"><div class="c-event-calendar-item_time"><span>18:00 Uhr</span></div>
            <a class="c-event-calendar-item_content" href="https://www.urania.de/event/$slug/">
            <h3 class="o-h3">$title</h3><h6 class="o-h6">$format</h6></a></div>"""
        val html =
            """<div class="c-event-calendar_day js-day" data-day="05-do-11-2026"><div class="c-event-calendar_day_events">""" +
                item("ernte", "Ernte und Ausblick", "Workshop im Urania-Garten") +
                item("kiez", "Fair und nachhaltig in Zehlendorf", "Kiezspaziergang") +
                item("pubquiz", "Das philosophische Pubquiz", "Workshop") +
                "</div></div>"

        val events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)

        events.map { it.sourceId to it.eventType } shouldBe listOf("urania:pubquiz" to EventType.QUIZ.name)
    }
}
