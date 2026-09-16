package de.norm.events.scraper.berghain

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [BerghainOverviewPageScraper], parsing saved snapshots of both
 * source pages that share the template: the main `/de/program/` page (Berghain
 * building floors → parties) and the `/de/program/kantine-am-berghain/`
 * concert-hall page. The clock is pinned before every fixture event so the
 * past-event cutoff keeps them all.
 */
class BerghainOverviewPageScraperTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC)
    private val scraper = BerghainOverviewPageScraper(clock)

    private val berghainUrl = "https://www.berghain.berlin/de/program/"
    private val kantineUrl = "https://www.berghain.berlin/de/program/kantine-am-berghain/"

    private fun loadFixture(path: String): String =
        javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    @Test
    fun `parses every event block on the main program page`() {
        val events = scraper.scrape(Jsoup.parse(loadFixture("scraper/berghain/berghain-overview.html"), berghainUrl), berghainUrl)
        events shouldHaveSize 21
    }

    @Test
    fun `parses a fully-populated club-floor event as a DJ party`() {
        val events = scraper.scrape(Jsoup.parse(loadFixture("scraper/berghain/berghain-overview.html"), berghainUrl), berghainUrl)
        val event = events.first { it.title == "BUTOH Batorū" }

        event.eventDate shouldBe LocalDate.of(2026, 7, 16)
        event.doorsTime.shouldBeNull()
        event.startTime shouldBe LocalTime.of(21, 0)
        event.eventType shouldBe EventType.PARTY.name
        event.genre shouldBe "Techno"
        event.sourceUrl shouldBe "https://www.berghain.berlin/de/event/80835/"
        event.sourceId shouldBe "berghain:80835"
        // Each act is tagged with the floor (stage) it plays.
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist("Hurricane Alexander", "DJ", "Berghain"),
                ScrapedArtist("Amanda Mussi", "DJ", "Berghain"),
                ScrapedArtist("Magna Pia", "DJ", "Berghain"),
                ScrapedArtist("X TiN", "DJ", "Berghain")
            )
    }

    @Test
    fun `parses every event block on the Kantine page as concerts with doors and start times`() {
        val events = scraper.scrape(Jsoup.parse(loadFixture("scraper/berghain/kantine-overview.html"), kantineUrl), kantineUrl)
        events shouldHaveSize 46

        val event = events.first { it.sourceId == "berghain:82242" }
        event.title shouldBe "Lenge"
        event.eventDate shouldBe LocalDate.of(2026, 7, 17)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.eventType shouldBe EventType.CONCERT.name
        // The Kantine concert hall hosts varied bills, so its floor yields no genre default.
        event.genre.shouldBeNull()
        event.artists shouldContainExactly listOf(ScrapedArtist("Lenge", "HEADLINER", "Kantine am Berghain"))
    }

    @Test
    fun `tags each act with the floor it plays across a multi-floor event`() {
        val html =
            """
            <html><body>
              <a href="/de/event/9/">
                <p>Samstag <span class="font-bold">25.07.2026</span> beginn 23:59</p>
                <h2>Klubnacht</h2>
                <h3>Berghain</h3>
                <h4><span class="font-bold"><span>Marcel Dettmann</span></span></h4>
                <h3>Panorama Bar</h3>
                <h4><span class="font-bold"><span>Ryan Elliott</span> <span class="uppercase">b2b</span> <span>Tama Sumo</span></span></h4>
              </a>
            </body></html>
            """.trimIndent()
        val events = scraper.scrape(Jsoup.parse(html, berghainUrl), berghainUrl)

        events shouldHaveSize 1
        // A multi-floor night joins its distinct floor genres in listing order.
        events.first().genre shouldBe "Techno, House"
        // Each act carries its own floor as stage; the Panorama Bar b2b splits into two DJs.
        events.first().artists shouldContainExactly
            listOf(
                ScrapedArtist("Marcel Dettmann", "DJ", "Berghain"),
                ScrapedArtist("Ryan Elliott", "DJ", "Panorama Bar"),
                ScrapedArtist("Tama Sumo", "DJ", "Panorama Bar")
            )
    }

    @Test
    fun `drops events dated before today`() {
        val lateClock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)
        val events =
            BerghainOverviewPageScraper(lateClock)
                .scrape(Jsoup.parse(loadFixture("scraper/berghain/berghain-overview.html"), berghainUrl), berghainUrl)
        events.shouldBeEmpty()
    }

    // The Kantine writes a cancellation into the heading; the persistence boundary reads it (#1493).
    @Test
    fun `stores a heading that ends in Abgesagt as a cancelled concert under the bare name`() {
        val html =
            """
            <html><body>
              <a href="/de/event/82554/">
                <p>Mittwoch <span class="font-bold">16.09.2026</span> tür 19:00 beginn 20:00</p>
                <h2>Olga Myko - Abgesagt</h2>
                <h3>Kantine am Berghain</h3>
                <h4><span class="font-bold"><span>Olga Myko</span> <span class="uppercase">Live</span></span></h4>
              </a>
            </body></html>
            """.trimIndent()
        val event = scraper.scrape(Jsoup.parse(html, kantineUrl), kantineUrl).single()

        event.title shouldBe "Olga Myko - Abgesagt"
        event.artists.map { it.name } shouldBe listOf("Olga Myko")
        val entity = event.toEventEntity(venueId = 1, venueSlug = "kantine-am-berghain", eventSourceId = 1)
        entity.status shouldBe "CANCELLED"
        entity.title shouldBe "Olga Myko"
    }

    @Test
    fun `skips a block with no title or unparseable date without aborting the import`() {
        val html =
            """
            <html><body>
              <a href="/de/event/1/"><p>Montag <span class="font-bold">not a date</span> beginn 20:00</p><h2>Bad Date</h2></a>
              <a href="/de/event/2/"><p>Montag <span class="font-bold">20.07.2026</span> beginn 20:00</p></a>
              <a href="/de/event/3/"><p>Montag <span class="font-bold">21.07.2026</span> beginn 22:00</p><h2>Good</h2><h3>Säule</h3></a>
            </body></html>
            """.trimIndent()
        val events = scraper.scrape(Jsoup.parse(html, berghainUrl), berghainUrl)

        events shouldHaveSize 1
        events.first().title shouldBe "Good"
        events.first().eventType shouldBe EventType.PARTY.name
        events.first().genre shouldBe "Experimental"
    }
}
