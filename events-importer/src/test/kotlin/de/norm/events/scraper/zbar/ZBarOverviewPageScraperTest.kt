package de.norm.events.scraper.zbar

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [ZBarOverviewPageScraper], against a saved copy of the real programme page. */
class ZBarOverviewPageScraperTest {
    private val scraper = ZBarOverviewPageScraper()
    private val baseUrl = "https://zbarberlin.com/kulturprogramm/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/zbar/zbar-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun page(vararg blocks: String) = Jsoup.parse("""<div class="entry-content">${blocks.joinToString("")}</div>""", baseUrl)

    private fun dateLine(text: String) = """<p class="has-secondary-background-color has-background"><strong>$text</strong></p>"""

    @Test
    fun `scrape reads every dated night`() {
        events shouldHaveSize 22
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 21)
        events.last().eventDate shouldBe LocalDate.of(2026, 12, 5)
    }

    @Test
    fun `scrape maps a fully populated night`() {
        val event = events.single { it.eventDate == LocalDate.of(2026, 10, 21) }

        event.title shouldBe "Hold my Beer | A Storytelling show"
        event.eventType shouldBe "COMEDY"
        event.startTime shouldBe LocalTime.of(20, 0)
        event.promoters shouldContainExactly listOf("Nav Jain")
        event.ticketUrl shouldBe "https://www.eventbrite.com/e/hold-my-beer-a-storytelling-show-tickets-2002066203910"
        event.imageUrl shouldBe "https://zbarberlin.com/wp-content/uploads/2026/10/new-hold-my-beer-poster.png"
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "z_bar:2026-10-21-hold-my-beer-a-storytelling-show"
        event.description!! shouldStartWith "Get ready to laugh and gasp"
        event.description shouldNotContain "©"
        event.description shouldNotContain "instagram.com"
    }

    @Test
    fun `one date line with two dates is two nights`() {
        val slams = events.filter { it.title == "Guerilla Slam" }

        slams.map { it.eventDate } shouldContainExactly listOf(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 26))
        slams.first().eventType shouldBe "READING"
    }

    @Test
    fun `a night without a heading takes its title from its Eventbrite link`() {
        events.single { it.eventDate == LocalDate.of(2026, 12, 5) && it.startTime == LocalTime.of(22, 0) }.title shouldBe
            "Unsafe Space A Dark Comedy Open Mic About Trauma"
    }

    @Test
    fun `a night named only on its flyer is skipped`() {
        events.map { it.eventDate } shouldNotContain LocalDate.of(2026, 10, 22)
    }

    @Test
    fun `the date typo 14 11 comma 2026 reads as a date and not as a clock`() {
        events.single { it.eventDate == LocalDate.of(2026, 11, 14) }.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `an Arabic date line beside the Latin one opens no night of its own`() {
        val event = events.single { it.eventDate == LocalDate.of(2026, 11, 5) }

        event.title shouldBe "„Laugh with Us“ Arabic Stand Up Comedy Night"
        event.ticketUrl shouldBe "https://levanto-entertainment.ticket-souq.com/events/172321"
    }

    @Test
    fun `a free reading is typed and marked free, and its credit names the promoter`() {
        val event = events.single { it.title == "Lesebühne DEO" }

        event.eventType shouldBe "READING"
        event.free shouldBe true
        event.promoters shouldContainExactly listOf("F. Hauser")
        event.ticketUrl.shouldBeNull()
    }

    @Test
    fun `a short film night is a screening`() {
        events.single { it.title == "Short Cutz – Kurzfilmprogramm" }.eventType shouldBe "SCREENING"
    }

    @Test
    fun `a recurring night without a date and a Turkish date line are skipped`() {
        val page =
            page(
                dateLine("Every Sunday, 08:30pm"),
                "<h6>New York Comedy Show</h6>",
                dateLine("30 Ekim Cuma 20:00"),
                "<h6>KURULU DÜZEN Stand Up</h6>"
            )

        scraper.scrape(page, baseUrl).shouldBeEmpty()
    }

    @Test
    fun `a private booking is skipped and the comedy night after it stays`() {
        val page =
            page(
                dateLine("So. 01.11.2026, 18:00 Uhr"),
                "<h6><strong>Private Veranstaltung, Bar offen ab 18 Uhr</strong></h6>",
                dateLine("Sa. 07.11.2026, 18:00 Uhr"),
                "<h6>Geschlossene Gesellschaft</h6>",
                dateLine("Mo. 02.11.2026, 20:00 Uhr"),
                "<h6>Open Mic Comedy Night – no private veranstaltung, everyone welcome to the longest open mic in town</h6>"
            )

        val event = scraper.scrape(page, baseUrl).single()
        event.eventDate shouldBe LocalDate.of(2026, 11, 2)
        event.eventType shouldBe "COMEDY"
    }

    @Test
    fun `a month header ends the night before it and opens none`() {
        val page =
            page(
                dateLine("Sa. 21.11.2026, 20:00 Uhr"),
                "<h6>Lesung</h6>",
                """<p class="has-primary-background-color has-background">VORSCHAU</p>""",
                "<p>Text of the next section</p>"
            )

        val event = scraper.scrape(page, baseUrl).single()
        event.description.shouldBeNull()
    }

    @Test
    fun `a dotted time is not read as a clock`() {
        val page = page(dateLine("Sa. 21.11.2026, 20.00 Uhr"), "<h6>Lesung</h6>")

        scraper
            .scrape(page, baseUrl)
            .single()
            .startTime
            .shouldBeNull()
    }

    @Test
    fun `scrape returns no events for an empty page`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
