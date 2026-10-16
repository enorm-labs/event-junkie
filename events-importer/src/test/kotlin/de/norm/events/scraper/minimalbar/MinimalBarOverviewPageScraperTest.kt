package de.norm.events.scraper.minimalbar

import de.norm.events.scraper.AcceptedLimitations
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** Unit tests for [MinimalBarOverviewPageScraper], against a saved copy of the real `/dates/` page. */
class MinimalBarOverviewPageScraperTest {
    private val scraper = MinimalBarOverviewPageScraper()
    private val baseUrl = "https://minimal.berlin/dates/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/minimalbar/minimalbar-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun acts(date: String) = night(date).artists.map { it.name }

    private fun berlin(
        date: String,
        time: String
    ) = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), BERLIN).toInstant()

    @Test
    fun `scrape reads every night of the payload, more than a year ahead`() {
        events shouldHaveSize 99
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 15)
        events.last().eventDate shouldBe LocalDate.of(2027, 12, 11)
    }

    @Test
    fun `scrape maps a fully populated night with its running order`() {
        val event = night("2026-10-25")

        event.title shouldBe "U10247"
        event.startTime shouldBe LocalTime.of(20, 0)
        event.eventType shouldBe "PARTY"
        event.typeIsFallback shouldBe false
        event.free shouldBe true
        event.imageUrl shouldBe "https://i1.sndcdn.com/avatars-IWM26MSNOLbqdxyc-IsUwBQ-t500x500.jpg"
        event.sourceUrl shouldBe "https://minimal.berlin/dates/2026-10-25/"
        event.sourceId shouldBe "minimal_bar:2026-10-25"
        // The card joins the three acts into one line; the payload bills each with its slot, the last after midnight.
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist("Bruno Bleckmann", "DJ", setStart = berlin("2026-10-25", "20:00")),
                ScrapedArtist("Clarence", "DJ", setStart = berlin("2026-10-25", "21:00")),
                ScrapedArtist("maniac&me", "DJ", setStart = berlin("2026-10-26", "00:00"))
            )
    }

    @Test
    fun `scrape drops the house text every night carries`() {
        events.mapNotNull { it.description }.shouldBeEmpty()
    }

    @Test
    fun `scrape keeps the resident and drops the unnamed company`() {
        acts("2026-10-17") shouldBe listOf("Jago K.")
        acts("2026-10-30") shouldBe listOf("Sven Swade")
        acts("2026-11-06") shouldBe listOf("Bruno Otranto")
        // The same DJ billed bare on other nights, so one artist row serves both.
        acts("2026-11-10") shouldBe listOf("maniac&me")
    }

    @Test
    fun `scrape strips the bar's own name, an origin and a label from an act`() {
        acts("2026-10-16") shouldBe listOf("Luca")
        acts("2026-10-29") shouldBe listOf("Pablo Cornejo")
        acts("2026-10-24") shouldBe listOf("Mister Teaser")
    }

    @Test
    fun `scrape drops an unannounced slot and splits two acts in one slot`() {
        acts("2026-10-31") shouldBe listOf("maniac&me", "Stefano Razzo")
        acts("2026-12-19") shouldBe listOf("Gaamer")
        acts("2026-12-11") shouldBe listOf("Oaky", "Andi De Luxe")
    }

    @Test
    fun `scrape keeps a joined name that has no space around its ampersand`() {
        acts("2026-11-19") shouldBe listOf("Flandez", "maniac&me")
    }

    @Test
    fun `every night takes the sound the site states, since the payload names none`() {
        events.mapNotNull { it.genre }.shouldBeEmpty()
        AcceptedLimitations.withHouseGenre(EventSource.MINIMAL_BAR, events).map { it.genre }.distinct() shouldBe
            listOf("Minimal, House, Tech House")
    }

    @Test
    fun `one night with its own text keeps it while the others still drop the house text`() {
        fun night(
            day: Int,
            description: String
        ) = """{"slug":"2026-11-0$day","title":"Night $day","date":"2026-11-0${day}T20:00:00.000Z","startTime":"21:00",""" +
            """"category":"dj-set","description":"$description","lineup":[]}"""
        val payload = """{"events":[${night(1, "House text")},${night(2, "House text")},${night(3, "Vinyl only tonight.")}]}"""

        scrapePayload(payload).map { it.description } shouldBe listOf(null, null, "Vinyl only tonight.")
    }

    private fun scrapePayload(payload: String): List<ScrapedEvent> {
        val script = "self.__next_f.push([1,${JsonMapper.builder().build().writeValueAsString(payload)}])"
        return scraper.scrape(Jsoup.parse("<html><body><script>$script</script></body></html>", baseUrl), baseUrl)
    }

    @Test
    fun `scrape survives a page with no flight payload at all`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Soon</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `scrape skips a night with no usable date and keeps a description that is the night's own`() {
        val payload =
            """{"events":[""" +
                """{"slug":"2026-11-01","title":"Lazy.Sunday","date":"soon","startTime":"21:00","lineup":[]},""" +
                """{"slug":"2026-11-02","title":"Manic.Monday","date":"2026-11-02T20:00:00.000Z","startTime":"21:00",""" +
                """"category":"live","description":"A live set from the back room.","lineup":[]}]}"""
        val event = scrapePayload(payload).single()

        event.title shouldBe "Manic.Monday"
        event.description shouldBe "A live set from the back room."
        event.typeIsFallback shouldBe true
        event.imageUrl.shouldBeNull()
    }
}
