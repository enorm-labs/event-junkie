package de.norm.events.scraper.frachtkante

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** The fixture was saved on 2026-10-13, with two events ahead. */
class FrachtkanteOverviewPageScraperTest {
    private val baseUrl = "https://frachtkante.de/de/"

    private val events by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/frachtkante/frachtkante-overview.html")!!
                .bufferedReader()
                .readText()
        scrape(html)
    }

    private fun scrape(html: String): List<ScrapedEvent> = FrachtkanteOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl)

    private fun titled(title: String) = events.single { it.title == title }

    private fun article(
        datetime: String,
        printed: String,
        title: String,
        body: String = ""
    ) = """
        <article>
            <time datetime="$datetime"><span>1</span><span>Jan</span><span>2027</span></time>
            <div>
                <div>$printed</div>
                <h3>$title</h3>
                $body
            </div>
        </article>
        """

    @Test
    fun `reads each event once, although the section is rendered twice`() {
        events.map { it.title } shouldContainExactly listOf("khisdapaze Open Air: TXL Summer Closing", "khisdapaze Wintermarkt 2.0")
    }

    @Test
    fun `reads an open air with its clock range and ticket link`() {
        val event = titled("khisdapaze Open Air: TXL Summer Closing")

        event.eventType shouldBe EventType.PARTY.name
        event.eventDate shouldBe LocalDate.of(2026, 10, 17)
        event.startTime shouldBe LocalTime.of(12, 0)
        event.endDate shouldBe LocalDate.of(2026, 10, 17)
        event.endTime shouldBe LocalTime.of(23, 0)
        event.ticketUrl shouldBe "https://tickets.khisdapaze.de/kdp/summer-closing/"
        event.description.shouldBeNull()
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "frachtkante:2026-10-17-khisdapaze-open-air-txl-summer-closing"
    }

    @Test
    fun `reads an all-day market over two days`() {
        val event = titled("khisdapaze Wintermarkt 2.0")

        event.eventType shouldBe EventType.OTHER.name
        event.eventDate shouldBe LocalDate.of(2026, 11, 28)
        event.startTime.shouldBeNull()
        event.endDate shouldBe LocalDate.of(2026, 11, 29)
        event.endTime.shouldBeNull()
        event.ticketUrl.shouldBeNull()
    }

    @Test
    fun `rolls an end past midnight to the next day and keeps a blurb`() {
        val event =
            scrape(
                article(
                    "2027-01-08T22:00:00+01:00",
                    "<span>8. Januar 2027</span><span>22:00 - 06:00 Uhr</span>",
                    "Konzert im Hangar",
                    """<div class="festival-styled"><p>Drei Bands im Hangar.</p><p>Tickets: https://example.org/t</p></div>"""
                )
            ).single()

        event.eventType shouldBe EventType.CONCERT.name
        event.endDate shouldBe LocalDate.of(2027, 1, 9)
        event.endTime shouldBe LocalTime.of(6, 0)
        event.description shouldBe "Drei Bands im Hangar."
    }

    @Test
    fun `skips an event without a usable date`() {
        scrape(article("soon", "<span>bald</span>", "Flohmarkt")) shouldHaveSize 0
    }

    @Test
    fun `an empty programme yields nothing`() {
        scrape("<html><body><h1>Veranstaltungen</h1></body></html>") shouldHaveSize 0
    }
}
