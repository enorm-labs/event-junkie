package de.norm.events.scraper.thedoor

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

/** Unit tests for [TheDoorEventsPageScraper], against a saved copy of the real events page. */
class TheDoorEventsPageScraperTest {
    private val scraper = TheDoorEventsPageScraper()
    private val baseUrl = "https://thedoor.club/events/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/thedoor/thedoor-events.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun djs(date: String) = night(date).artists.map { it.name }

    private fun card(
        title: String,
        lineup: String
    ) = """
        <div class="ct-div-block card-repeater-event"><img src="https://thedoor.club/cover.jpg">
          <div><div><div class="ct-text-block">SA</div><div class="ct-text-block"><span class="ct-span">07.11.2026</span></div>
          <div class="ct-text-block"><span class="ct-span">23:00</span></div></div>
          <h2 class="ct-headline"><span class="ct-span">$title</span></h2>
          <div><div class="ct-text-block">LINEUP:</div><div class="ct-text-block"><span class="ct-span">$lineup</span></div></div></div>
        </div>
        """

    private fun scrapeCard(
        title: String,
        lineup: String
    ) = scraper.scrape(Jsoup.parse(card(title, lineup), baseUrl), baseUrl)

    @Test
    fun `scrape reads every booked night and skips the placeholder Saturday`() {
        events shouldHaveSize 9
        events.map { it.eventDate.dayOfMonth } shouldContainExactly listOf(2, 3, 9, 10, 16, 17, 23, 30, 31)
    }

    @Test
    fun `scrape maps a night with its lineup, time and flyer`() {
        val event = night("2026-10-17")

        event.title shouldBe "Funk You Musik Label Night / Release Party"
        event.eventType shouldBe "PARTY"
        event.startTime shouldBe LocalTime.of(23, 0)
        event.doorsTime.shouldBeNull()
        event.imageUrl shouldBe "https://thedoor.club/wp-content/uploads/2026/10/plastik-funk-17-10-2026.jpg"
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "the_door:2026-10-17"
        event.artists.map { it.role }.distinct() shouldContainExactly listOf("DJ")
        djs("2026-10-17") shouldContainExactly listOf("PLASTIK FUNK", "MELENDEZ", "3NERGY", "FRANKIE BALOU")
    }

    @Test
    fun `a single DJ and a crew billing are each one act`() {
        djs("2026-10-02") shouldContainExactly listOf("NICOLAI KUBERA")
        djs("2026-10-30") shouldContainExactly listOf("ABF & Friends")
    }

    @Test
    fun `a weekday title with a real lineup is a night`() {
        val event = scrapeCard("Saturday", "DJ ONE, DJ TWO").single()

        event.title shouldBe "Saturday"
        event.artists.map { it.name } shouldContainExactly listOf("DJ ONE", "DJ TWO")
    }

    @Test
    fun `a named night still waiting for its lineup is kept without artists`() {
        val event = scrapeCard("Pre Halloween", "Coming Soon").single()

        event.title shouldBe "Pre Halloween"
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `a German weekday placeholder with TBA is skipped`() {
        scrapeCard("Freitag", "TBA").shouldBeEmpty()
    }

    @Test
    fun `a page without a grid yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Closed</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
