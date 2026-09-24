package de.norm.events.scraper.urania

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** Unit tests for the field mapping in `UraniaEventFields.kt`. */
class UraniaEventFieldsTest {
    @Test
    fun `types the house's talk formats as spoken word`() {
        listOf("Vortrag", "Podiumsdiskussion zur Buchpremiere", "Schönheitssalon", "Live-Podcast", null)
            .map { uraniaEventType(it) }
            .toSet() shouldBe setOf(EventType.READING.name)
    }

    @Test
    fun `types a workshop as other wherever the label names it`() {
        // The live formats as of #1906.
        listOf("Workshop im Urania-Garten", "Film und Workshop für Schulklassen", "Workshop für Schulklassen", "Workshop")
            .map { uraniaEventType(it) }
            .toSet() shouldBe setOf(EventType.OTHER.name)
    }

    @Test
    fun `lets the shared table and the house's synonyms decide first`() {
        uraniaEventType("Konzert") shouldBe EventType.CONCERT.name
        uraniaEventType("Film") shouldBe EventType.SCREENING.name
    }

    @Test
    fun `bills a talk's speakers and nobody at a workshop`() {
        val billing = "Léna Kútvölgyi, Sara Stenczer"
        uraniaSpeakers(billing, EventType.READING.name).map { it.name } shouldBe listOf("Léna Kútvölgyi", "Sara Stenczer")
        uraniaSpeakers(billing, EventType.OTHER.name).shouldBeEmpty()
    }

    @Test
    fun `a calendar workshop is stored as other with no artists`() {
        val baseUrl = "https://www.urania.de/kalender/"
        val document =
            Jsoup.parse(
                """
                <html><body><div class="c-event-calendar_day js-day" data-day="08-do-10-2026">
                <div class="c-event-calendar-item">
                <div class="c-event-calendar-item_time">16:00 Uhr</div>
                <a class="c-event-calendar-item_content" href="https://www.urania.de/event/ernte-und-ausblick/">
                <h5 class="o-h6">AUFBAUAUF</h5><h3 class="o-h3">Ernte und Ausblick</h3>
                <h6 class="o-h6">Workshop im Urania-Garten</h6>
                <div class="c-event-calendar-item_content_text">Léna Kútvölgyi, Sara Stenczer</div></a>
                </div></div></body></html>
                """.trimIndent(),
                baseUrl
            )

        val events = UraniaCalendarPageScraper().scrape(document, baseUrl)

        events shouldHaveSize 1
        events.single().eventType shouldBe EventType.OTHER.name
        events.single().artists.shouldBeEmpty()
    }
}
