package de.norm.events.scraper.urbanspree

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [UrbanSpreeDetailPageScraper], parsing saved snapshots of
 * `/program/concerts/<slug>.html` detail pages.
 */
class UrbanSpreeDetailPageScraperTest {
    private val scraper = UrbanSpreeDetailPageScraper()

    private val concertUrl = "https://www.urbanspree.com/program/concerts/twin-noir-hinfort-urban-spree,-berlin.html"
    private val cancelledUrl = "https://www.urbanspree.com/program/concerts/som-berlin-urban-spree.html"
    private val freeUrl = "https://www.urbanspree.com/program/concerts/this-eternal-decay-(-dark-wave-it)-night-nail-(dark-wave-us/de).html"

    private fun fixture(
        name: String,
        baseUrl: String
    ): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/urbanspree/$name")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    private val klubnachtUrl = "https://www.urbanspree.com/program/concerts/urban-spree-klubnacht-005.html"

    @Test
    fun `scrape resolves a club night's 23-59 placeholder and bills its DJ sets`() {
        val event = scraper.scrape(fixture("urbanspree-detail-klubnacht.html", klubnachtUrl), klubnachtUrl).shouldNotBeNull()

        event.eventDate shouldBe LocalDate.of(2026, 9, 25)
        event.startTime shouldBe LocalTime.of(21, 0)
        event.eventType shouldBe EventType.PARTY.name
        event.artists.map { it.name to it.role } shouldContainExactly
            listOf("Albert Kraft" to "DJ", "Key Clef" to "DJ", "Daraio" to "DJ")
    }

    @Test
    fun `scrape keeps 23-59 when the description's stamp names another date`() {
        val document = fixture("urbanspree-detail-klubnacht.html", klubnachtUrl)
        document.selectFirst(".rte.tv-content p:contains(25.09.26)")!!.text("26.09.26 21:00 — LATE")

        scraper.scrape(document, klubnachtUrl).shouldNotBeNull().startTime shouldBe LocalTime.of(23, 59)
    }

    private fun scrapeConcert() = scraper.scrape(fixture("urbanspree-detail-concert.html", concertUrl), concertUrl)

    @Test
    fun `scrape reads all fields of a fully populated detail page`() {
        val event = scrapeConcert().shouldNotBeNull()

        event.title shouldBe "TWIN NOIR + HINFORT"
        event.eventType shouldBe EventType.CONCERT.name
        event.eventDate shouldBe LocalDate.of(2026, 12, 12)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.pricePresale shouldBe BigDecimal("25.00")
        event.free shouldBe false
        event.status shouldBe EventStatus.SCHEDULED.name
        event.ticketUrl.shouldNotBeNull() shouldStartWith "https://shotgun.live/en/events/twin-noir-hinfort-cassiopeia-berlin"
        event.promoters shouldContainExactly listOf("Aufnahme + wiedergabe")
        event.description.shouldNotBeNull() shouldContain "Berlin-based project TWIN NOIR transforms analog rawness"
        event.sourceUrl shouldBe concertUrl
        event.sourceId shouldBe "urban_spree:concerts/twin-noir-hinfort-urban-spree,-berlin"
        // Root-relative image path resolved through the page's <base> tag.
        event.imageUrl.shouldNotBeNull() shouldStartWith "https://www.urbanspree.com/assets/components/phpthumbof/cache/IMG_1389."
    }

    @Test
    fun `scrape splits a co-billed title into headliner artists`() {
        // The hero title is untruncated here, unlike the listing card's ellipsised copy.
        scrapeConcert().shouldNotBeNull().artists.map { it.name to it.role } shouldContainExactly
            listOf("TWIN NOIR" to "HEADLINER", "HINFORT" to "HEADLINER")
    }

    @Test
    fun `scrape reads the date from the hero rather than the upcoming-events slider`() {
        // The page re-renders the whole listing as an "upcoming events" slider using the same
        // card markup, whose first entry is a different event dated 2027-04-23.
        scrapeConcert().shouldNotBeNull().eventDate shouldBe LocalDate.of(2026, 12, 12)
    }

    @Test
    fun `scrape reads a cancelled event's status and promoter`() {
        val event = scraper.scrape(fixture("urbanspree-detail-cancelled.html", cancelledUrl), cancelledUrl).shouldNotBeNull()

        event.status shouldBe EventStatus.CANCELLED.name
        event.title shouldBe "SOM"
        event.artists.map { it.name } shouldContainExactly listOf("SOM")
        event.promoters shouldContainExactly listOf("Landstreicher Konzerte")
        // A cancelled show keeps its price and ticket link — the shop handles refunds.
        event.pricePresale shouldBe BigDecimal("29.90")
    }

    @Test
    fun `splits two promoters joined with an ampersand and keeps a plus inside one name`() {
        val document = fixture("urbanspree-detail-concert.html", concertUrl)
        document.select(".info-data").first { it.text() == "Aufnahme + wiedergabe" }.text("Positive Transmitter & Crunch Tapes")

        scraper.scrape(document, concertUrl).shouldNotBeNull().promoters shouldContainExactly
            listOf("Positive Transmitter", "Crunch Tapes")
        // The fixture's own value is one label, and stays one promoter (asserted above).
        scrapeConcert().shouldNotBeNull().promoters shouldContainExactly listOf("Aufnahme + wiedergabe")
    }

    // #1356: one party name carries the venue's own join character.
    @Test
    fun `keeps a single name that carries an ampersand whole`() {
        val document = fixture("urbanspree-detail-concert.html", concertUrl)
        document.select(".info-data").first { it.text() == "Aufnahme + wiedergabe" }.text("Pure Obsessions & Red Nights")

        scraper.scrape(document, concertUrl).shouldNotBeNull().promoters shouldContainExactly listOf("Pure Obsessions & Red Nights")
    }

    @Test
    fun `scrape flags free entry and drops the empty ticket link`() {
        val event = scraper.scrape(fixture("urbanspree-detail-free.html", freeUrl), freeUrl).shouldNotBeNull()

        event.free shouldBe true
        event.pricePresale.shouldBeNull()
        // The venue always renders the "Buy tickets" anchor; with href="" it must not
        // resolve to the site root via the page's <base> tag.
        event.ticketUrl.shouldBeNull()
        event.eventDate shouldBe LocalDate.of(2026, 9, 25)
        event.startTime shouldBe LocalTime.of(21, 0)
        event.sourceId shouldBe "urban_spree:concerts/this-eternal-decay-(-dark-wave-it)-night-nail-(dark-wave-us/de)"
    }

    @Test
    fun `scrape bills a title's support note as support artists instead of gluing it onto the headliner`() {
        val html =
            """
            <html><head><base href="https://www.urbanspree.com/"></head><body><div id="pseudo-card">
              <div class="parent-name">Concerts</div>
              <h1 class="title">WISBORG Phantomschmerz Tour - BERLIN | Special Guest: The Fright</h1>
              <div class="ct-dates"><ul>
                <li class="list-group-item infos">Nov 21, 2026</li>
                <li class="list-group-item infos">20:00</li>
              </ul></div>
            </div></body></html>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, concertUrl), concertUrl).shouldNotBeNull()

        // The mid-title city is now a trailing tail of the headline, so it is stripped too.
        event.title shouldBe "WISBORG Phantomschmerz Tour"
        event.subtitle shouldBe "| Special Guest: The Fright"
        event.artists.map { it.name to it.role } shouldContainExactly
            listOf("WISBORG" to "HEADLINER", "The Fright" to "SUPPORT")
    }

    @Test
    fun `scrape returns null when the page carries no event hero`() {
        val html = """<html><body><main><h1>Page not found</h1></main></body></html>"""

        scraper.scrape(Jsoup.parse(html, concertUrl), concertUrl).shouldBeNull()
    }

    @Test
    fun `scrape returns null when the hero has no title`() {
        val html = """<html><body><div id="pseudo-card"><div class="parent-name">Concerts</div></div></body></html>"""

        scraper.scrape(Jsoup.parse(html, concertUrl), concertUrl).shouldBeNull()
    }

    @Test
    fun `scrape leaves the date unresolved when the hero date cannot be parsed`() {
        val html =
            """
            <html><body><div id="pseudo-card">
              <h1 class="title">Mystery Show</h1>
              <div class="ct-dates"><ul><li class="list-group-item infos">soon</li></ul></div>
            </div></body></html>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, concertUrl), concertUrl).shouldNotBeNull()

        // The importer overwrites this with the listing card's machine-readable date.
        event.eventDate shouldBe de.norm.events.scraper.UNRESOLVED_EVENT_DATE
    }
}
