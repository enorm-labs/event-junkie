package de.norm.events.scraper.volksbuehne

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.time.LocalDate

class VolksbuehneEventPageScraperTest {
    private val scraper = VolksbuehneEventPageScraper()

    @Test
    fun `reads the text, the JSON-LD image and the schema type`() {
        val page = scraper.scrape(page("volksbuehne-event-a-year-without-summer.html")).shouldNotBeNull()

        page.description.shouldNotBeNull() shouldStartWith "BEATRICE CORDUA 1943 – 2025"
        page.imageUrl.shouldNotBeNull() shouldStartWith "https://www.volksbuehne-berlin.de/media/images/"
        page.eventType shouldBe EventType.SHOW
    }

    @Test
    fun `reads the English page's text for the second language`() {
        scraper.description(page("volksbuehne-event-space-afrika-en.html")).shouldNotBeNull() shouldStartWith "Some music demands our attention."
        scraper.scrape(page("volksbuehne-event-space-afrika.html")).shouldNotBeNull().eventType shouldBe EventType.CONCERT
    }

    @Test
    fun `confirms a fallback type from the page and leaves a labelled one alone`() {
        val page = VolksbuehneEventPage(description = "Text", imageUrl = null, eventType = EventType.READING)
        val fallback = event(EventType.SHOW, typeIsFallback = true)
        val labelled = event(EventType.CONCERT, typeIsFallback = false)

        page.applyTo(fallback).eventType shouldBe EventType.READING.name
        page.applyTo(fallback).typeIsFallback shouldBe false
        page.applyTo(labelled).eventType shouldBe EventType.CONCERT.name
        page.applyTo(labelled).description shouldBe "Text"
    }

    @Test
    fun `types a ball or a festival bar as other`() {
        val html = """<script type="application/ld+json">{"@type": "SocialEvent", "name": "Community Bar"}</script>"""

        scraper.scrape(Jsoup.parse(html)).shouldNotBeNull().eventType shouldBe EventType.OTHER
    }

    @Test
    fun `returns nothing for a page without an event or a text`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>")).shouldBeNull()
    }

    private fun event(
        type: EventType,
        typeIsFallback: Boolean
    ) = ScrapedEvent(
        title = "Row",
        eventType = type.name,
        typeIsFallback = typeIsFallback,
        eventDate = LocalDate.of(2026, 11, 3),
        sourceUrl = "https://www.volksbuehne-berlin.de/produktionen/row/20261103-1830/",
        sourceId = "volksbuehne:row-20261103-1830"
    )

    private fun page(name: String): Document =
        Jsoup.parse(
            requireNotNull(javaClass.classLoader.getResourceAsStream("scraper/volksbuehne/$name")).bufferedReader().readText(),
            "https://www.volksbuehne-berlin.de/"
        )
}
