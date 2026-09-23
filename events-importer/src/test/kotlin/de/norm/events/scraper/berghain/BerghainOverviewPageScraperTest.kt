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
        // Each act is tagged with the floor (stage) it plays, and the one billed `Live` performs.
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist("Hurricane Alexander", "HEADLINER", "Berghain"),
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

    // The venue also writes the join inside the name span, where no marker class reaches it (#1759).
    @Test
    fun `splits a back-to-back slot the venue wrote inside one name span`() {
        val html =
            """
            <html><body>
              <a href="/de/event/80845/">
                <p>Donnerstag <span class="font-bold">24.09.2026</span> beginn 22:00</p>
                <h2>Terenor</h2>
                <h3>Säule</h3>
                <h4>
                  <span class="font-bold">
                    <span class="xs:whitespace-no-wrap">KĀ</span>
                    <span class="text-sm font-bold uppercase">Live</span>,
                  </span>
                  <span class="font-bold">
                    <span class="xs:whitespace-no-wrap">Agata B2B Cunt Remember</span>,
                  </span>
                  <span class="font-bold">
                    <span class="xs:whitespace-no-wrap">Egregore B2B Jolly</span>
                  </span>
                </h4>
              </a>
            </body></html>
            """.trimIndent()
        val event = scraper.scrape(Jsoup.parse(html, berghainUrl), berghainUrl).single()

        // Five performers on one floor, not three. The prose names all five, and KĀ is billed `Live`.
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist("KĀ", "HEADLINER", "Säule"),
                ScrapedArtist("Agata", "DJ", "Säule"),
                ScrapedArtist("Cunt Remember", "DJ", "Säule"),
                ScrapedArtist("Egregore", "DJ", "Säule"),
                ScrapedArtist("Jolly", "DJ", "Säule")
            )
    }

    // The marker is the venue saying the act performs, and it sits inside that act's wrapper (#1787).
    @Test
    fun `bills an act marked Live as a headliner and leaves the rest of the floor DJs`() {
        val html =
            """
            <html><body>
              <a href="/de/event/82270/">
                <p>Dienstag <span class="font-bold">13.10.2026</span> beginn 20:00</p>
                <h2>Krallice</h2>
                <h3>Berghain</h3>
                <h4>
                  <span class="font-bold">
                    <span class="xs:whitespace-no-wrap">Krallice</span>
                    <span class="text-sm font-bold uppercase">Live</span>,
                  </span>
                  <span class="font-bold">
                    <span class="xs:whitespace-no-wrap">Marcel Dettmann</span>
                  </span>
                </h4>
              </a>
            </body></html>
            """.trimIndent()
        val event = scraper.scrape(Jsoup.parse(html, berghainUrl), berghainUrl).single()

        // A band and a DJ on one bill, told apart by the only place the venue publishes it.
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist("Krallice", "HEADLINER", "Berghain"),
                ScrapedArtist("Marcel Dettmann", "DJ", "Berghain")
            )
    }

    // The venue wrote five DJs of one night into a single name span; the span was stored as one act.
    @Test
    fun `a comma inside a name span separates the performers of one slot`() {
        val html =
            """
            <html><body>
              <a href="/de/event/94001/">
                <p>Freitag <span class="font-bold">25.09.2026</span> beginn 23:59</p>
                <h2>wsnwg</h2>
                <h3>Berghain</h3>
                <h4>
                  <span class="font-bold">
                    <span class="xs:whitespace-no-wrap">Rødhåd, Dasha Rush, Megan Leber, Speedy J, UFO95</span>
                  </span>
                </h4>
              </a>
            </body></html>
            """.trimIndent()
        val event = scraper.scrape(Jsoup.parse(html, berghainUrl), berghainUrl).single()

        event.artists shouldContainExactly
            listOf(
                ScrapedArtist("R\u00f8dh\u00e5d", "DJ", "Berghain"),
                ScrapedArtist("Dasha Rush", "DJ", "Berghain"),
                ScrapedArtist("Megan Leber", "DJ", "Berghain"),
                ScrapedArtist("Speedy J", "DJ", "Berghain"),
                ScrapedArtist("UFO95", "DJ", "Berghain")
            )
    }

    // A `Live` marker after a comma list bills every performer in it, the way it does after a b2b.
    @Test
    fun `a Live marker after a comma list bills every act in it`() {
        val html =
            """
            <html><body>
              <a href="/de/event/94002/">
                <p>Freitag <span class="font-bold">25.09.2026</span> beginn 23:59</p>
                <h2>wsnwg</h2>
                <h3>Berghain</h3>
                <h4>
                  <span class="font-bold">
                    <span class="xs:whitespace-no-wrap">Nonn, Ruhig</span>
                    <span class="text-sm font-bold uppercase">Live</span>,
                  </span>
                  <span class="font-bold">
                    <span class="xs:whitespace-no-wrap">Sedef Adası</span>
                  </span>
                </h4>
              </a>
            </body></html>
            """.trimIndent()
        val event = scraper.scrape(Jsoup.parse(html, berghainUrl), berghainUrl).single()

        event.artists shouldContainExactly
            listOf(
                ScrapedArtist("Nonn", "HEADLINER", "Berghain"),
                ScrapedArtist("Ruhig", "HEADLINER", "Berghain"),
                ScrapedArtist("Sedef Adas\u0131", "DJ", "Berghain")
            )
    }

    // `b2b` is an `uppercase` span too, and it joins two DJ sets rather than announcing a live act.
    @Test
    fun `leaves a back-to-back slot marked by its own span as two DJs`() {
        val html =
            """
            <html><body>
              <a href="/de/event/11/">
                <p>Samstag <span class="font-bold">25.07.2026</span> beginn 23:59</p>
                <h2>Klubnacht</h2>
                <h3>Panorama Bar</h3>
                <h4><span class="font-bold"><span>Ryan Elliott</span> <span class="uppercase">b2b</span> <span>Tama Sumo</span></span></h4>
              </a>
            </body></html>
            """.trimIndent()
        val event = scraper.scrape(Jsoup.parse(html, berghainUrl), berghainUrl).single()

        event.artists shouldContainExactly
            listOf(
                ScrapedArtist("Ryan Elliott", "DJ", "Panorama Bar"),
                ScrapedArtist("Tama Sumo", "DJ", "Panorama Bar")
            )
    }

    // A marker after a span the b2b split marks both halves, not the second one alone.
    @Test
    fun `bills both halves of a split slot live when the marker follows it`() {
        val html =
            """
            <html><body>
              <a href="/de/event/12/">
                <p>Samstag <span class="font-bold">25.07.2026</span> beginn 23:59</p>
                <h2>Klubnacht</h2>
                <h3>Säule</h3>
                <h4>
                  <span class="font-bold">
                    <span class="xs:whitespace-no-wrap">Agata B2B Cunt Remember</span>
                    <span class="text-sm font-bold uppercase">Live</span>
                  </span>
                </h4>
              </a>
            </body></html>
            """.trimIndent()
        val event = scraper.scrape(Jsoup.parse(html, berghainUrl), berghainUrl).single()

        event.artists shouldContainExactly
            listOf(
                ScrapedArtist("Agata", "HEADLINER", "Säule"),
                ScrapedArtist("Cunt Remember", "HEADLINER", "Säule")
            )
    }

    // Back-to-back is two DJs; a conjunction is not, and the venue bills both in the same shape.
    @Test
    fun `keeps a duo whose name carries a conjunction whole`() {
        val html =
            """
            <html><body>
              <a href="/de/event/10/">
                <p>Samstag <span class="font-bold">25.07.2026</span> beginn 23:59</p>
                <h2>Klubnacht</h2>
                <h3>Panorama Bar</h3>
                <h4><span class="font-bold"><span>Blasha &amp; Allatt</span></span></h4>
              </a>
            </body></html>
            """.trimIndent()
        val event = scraper.scrape(Jsoup.parse(html, berghainUrl), berghainUrl).single()

        event.artists.map { it.name } shouldBe listOf("Blasha & Allatt")
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
