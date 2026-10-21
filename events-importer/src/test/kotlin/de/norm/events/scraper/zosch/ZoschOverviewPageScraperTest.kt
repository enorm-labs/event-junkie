package de.norm.events.scraper.zosch

import de.norm.events.scraper.ScrapedArtist
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

/** Unit tests for [ZoschOverviewPageScraper], against a saved copy of the real start page. */
class ZoschOverviewPageScraperTest {
    private val scraper = ZoschOverviewPageScraper()
    private val baseUrl = "https://www.zosch-berlin.de/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/zosch/zosch-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(
        id: String = "e20261105",
        category: String = "Musik",
        time: String = "20:00 Uhr",
        title: String = "Insaiter Jazz Trio"
    ) = Jsoup.parse(
        """
        <div class="eventContainer"><div class="event col-12 lazy">
          <div class="subHeader"><span class="ml-2">Donnerstag</span><span class="ml-1">05.11.</span><span class="ml-1">$time</span>
            <span class="category mr-1">$category</span></div>
          <div class="title"><div class="p-0"><div class="theme d-inline mr-2"><span>Jazz</span></div> $title </div></div>
          <div id="$id" class="eventBody collapse"><div class="subtitle"><span>swing</span></div></div>
        </div></div>
        """.trimIndent(),
        baseUrl
    )

    @Test
    fun `scrape reads every night on the page`() {
        events shouldHaveSize 16
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 21)
        events.last().eventDate shouldBe LocalDate.of(2026, 12, 23)
    }

    @Test
    fun `scrape maps a fully populated night`() {
        val event = events.single { it.eventDate == LocalDate.of(2026, 10, 29) }

        event.title shouldBe "Jazzformation Just Friends"
        event.subtitle shouldBe "Jazzstandards"
        event.genre shouldBe "Jazz"
        event.eventType shouldBe "CONCERT"
        event.startTime shouldBe LocalTime.of(20, 0)
        event.imageUrl shouldBe "https://www.zosch-berlin.de/storage/media/images/158/F53KX1nzsG9g4xVCs62MDq7oTvbXIhsCm1fBoQsU.jpg"
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "zosch:2026-10-29-jazzformation-just-friends"
        event.ticketUrl.shouldBeNull()
        event.artists shouldContainExactly listOf(ScrapedArtist(name = "Jazzformation Just Friends", role = "HEADLINER", titleDerived = true))
    }

    @Test
    fun `the year comes from the body id, across the turn of the year`() {
        val event = scraper.scrape(night(id = "e20270107"), baseUrl).single()

        event.eventDate shouldBe LocalDate.of(2027, 1, 7)
    }

    @Test
    fun `a night without a printed time has no start time`() {
        scraper
            .scrape(night(time = ""), baseUrl)
            .single()
            .startTime
            .shouldBeNull()
    }

    @Test
    fun `a reading and a party take their own types`() {
        scraper.scrape(night(category = "Lesung"), baseUrl).single().eventType shouldBe "READING"
        scraper.scrape(night(category = "Party"), baseUrl).single().eventType shouldBe "PARTY"
    }

    @Test
    fun `a night without a dated body id or a title is skipped`() {
        scraper.scrape(night(id = "eventBody"), baseUrl).shouldBeEmpty()
        scraper.scrape(night(title = ""), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `an empty page yields no nights`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
