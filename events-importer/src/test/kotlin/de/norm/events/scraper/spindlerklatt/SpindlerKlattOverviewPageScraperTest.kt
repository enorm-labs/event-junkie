package de.norm.events.scraper.spindlerklatt

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [SpindlerKlattOverviewPageScraper], against a saved copy of the real club page. */
class SpindlerKlattOverviewPageScraperTest {
    private val scraper = SpindlerKlattOverviewPageScraper()
    private val baseUrl = "https://www.spindlerklatt.com/club"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/spindlerklatt/spindlerklatt-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun scrapeItems(vararg items: String) =
        scraper.scrape(
            Jsoup.parse(items.joinToString("") { "<div class=\"wixui-repeater__item\">$it</div>" }, baseUrl),
            baseUrl
        )

    private fun richText(html: String) = "<div data-testid=\"richTextElement\"><p>$html</p></div>"

    @Test
    fun `scrape reads every night of the programme`() {
        events shouldHaveSize 9
        events.map { it.eventDate.toString() } shouldContainExactly
            listOf(
                "2026-10-30",
                "2026-10-31",
                "2026-11-07",
                "2026-11-14",
                "2026-11-20",
                "2026-11-21",
                "2026-12-05",
                "2026-12-12",
                "2026-12-31"
            )
    }

    @Test
    fun `scrape maps a fully populated night`() {
        val event = night("2026-10-30")

        event.title shouldBe "Halloween Nightmare"
        event.eventType shouldBe "PARTY"
        event.startTime shouldBe LocalTime.of(22, 0)
        event.endTime.shouldBeNull()
        event.endDate.shouldBeNull()
        event.doorsTime.shouldBeNull()
        event.imageUrl shouldBe "https://static.wixstatic.com/media/3f2613_276ad0d489bd4de89669209d6b885b5d~mv2.jpg"
        event.ticketUrl shouldBe "https://partytickets.ditix.shop/event/8eafooaygyqcrlw3"
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "spindler_klatt:2026-10-30"
    }

    @Test
    fun `a time split across spans reads as one clock`() {
        night("2026-11-07").startTime shouldBe LocalTime.of(22, 30)
        night("2026-11-21").startTime shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `a time range sets the end time on the same day`() {
        val event = night("2026-11-20")

        event.title shouldBe "Ü60 Party"
        event.startTime shouldBe LocalTime.of(19, 0)
        event.endTime shouldBe LocalTime.of(23, 0)
        event.endDate shouldBe LocalDate.of(2026, 11, 20)
    }

    @Test
    fun `a title broken across lines is joined, and entities are decoded`() {
        night("2026-12-12").title shouldBe "BLN REUNION - Indoorfestival"
        night("2026-12-31").title shouldBe "New Years Eve - Discothèque 2026 / 2027"
        night("2026-10-31").title shouldBe "90er & 2000er Halloween Party"
    }

    @Test
    fun `a trailing non-breaking space is not part of the title`() {
        night("2026-11-07").title shouldBe "Blaulicht-Union Party"
    }

    @Test
    fun `an end time past midnight rolls to the next day`() {
        val event = scrapeItems(richText("Sa., 07.11.2026 | 23:00 - 05:00 Uhr") + richText("Late Night")).single()

        event.endTime shouldBe LocalTime.of(5, 0)
        event.endDate shouldBe LocalDate.of(2026, 11, 8)
    }

    @Test
    fun `an item without a date or a title is skipped and the others are kept`() {
        val events =
            scrapeItems(
                richText("Demnächst") + richText("Coming soon"),
                richText("Sa., 07.11.2026 | 22:00 Uhr"),
                richText("Sa., 14.11.2026 | 22:00 Uhr") + richText("Ü30 Party")
            )

        events.map { it.title } shouldContainExactly listOf("Ü30 Party")
    }

    @Test
    fun `an item without a flyer or a button has no image and no ticket link`() {
        val event = scrapeItems(richText("Sa., 14.11.2026 | 22:00 Uhr") + richText("Ü30 Party")).single()

        event.imageUrl.shouldBeNull()
        event.ticketUrl.shouldBeNull()
    }

    @Test
    fun `a page without a repeater yields no events`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Club</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
