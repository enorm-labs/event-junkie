package de.norm.events.scraper.urbanspree

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [UrbanSpreeOverviewPageScraper], parsing saved snapshots of the
 * `/program/` listing.
 *
 * `urbanspree-overview.html` and `urbanspree-overview-page2.html` are pages 1 and 2 of the
 * live listing; `urbanspree-overview-past-boundary.html` is the page whose nine cards
 * straddle the today cutoff and which also carries the cancelled and free-entry events.
 */
class UrbanSpreeOverviewPageScraperTest {
    private val scraper = UrbanSpreeOverviewPageScraper()

    private val listingUrl = "https://www.urbanspree.com/program/"
    private val page2Url = "https://www.urbanspree.com/program/?page=2"
    private val boundaryUrl = "https://www.urbanspree.com/program/?page=3"

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

    private fun scrapeListing() = scraper.scrape(fixture("urbanspree-overview.html", listingUrl), listingUrl)

    private fun scrapeBoundary() = scraper.scrape(fixture("urbanspree-overview-past-boundary.html", boundaryUrl), boundaryUrl)

    @Test
    fun `scrape extracts every card on the listing page`() {
        // pdoPage renders nine cards per page.
        scrapeListing() shouldHaveSize 9
    }

    @Test
    fun `scrape reads all fields of a fully populated card`() {
        val event = scrapeListing()[1]

        // The venue/city tail is stripped from the stored title.
        event.title shouldBe "TWIN NOIR + HINFORT"
        event.eventType shouldBe EventType.CONCERT.name
        // Date and start time come from the machine-readable data-dateStart attribute.
        event.eventDate shouldBe LocalDate.of(2026, 12, 12)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.pricePresale shouldBe BigDecimal("25.00")
        event.free shouldBe false
        event.status shouldBe EventStatus.SCHEDULED.name
        event.imageUrl shouldBe "https://www.urbanspree.com/assets/project/urbanspree/mediasource/IMG_1389.JPG"
        event.sourceUrl shouldBe "https://www.urbanspree.com/program/concerts/twin-noir-hinfort-urban-spree,-berlin.html"
        event.sourceId shouldBe "urban_spree:concerts/twin-noir-hinfort-urban-spree,-berlin"
    }

    @Test
    fun `scrape resolves relative links through the page's base tag, not the fetched page URL`() {
        // Hrefs are relative ("program/concerts/…") and the page carries
        // <base href="https://www.urbanspree.com/">. Resolving against the page URL instead
        // would produce a doubled "/program/program/…" path on every page past the first.
        val event = scraper.scrape(fixture("urbanspree-overview-page2.html", page2Url), page2Url).first()

        event.sourceUrl shouldBe "https://www.urbanspree.com/program/concerts/jud-urban-spree-berlin.html"
        event.sourceId shouldBe "urban_spree:concerts/jud-urban-spree-berlin"
    }

    @Test
    fun `scrape keeps cards in the listing's descending date order`() {
        // The pagination walk in UrbanSpreeWebsiteImporter depends on this ordering.
        scrapeListing().map { it.eventDate } shouldContainExactly
            listOf(
                LocalDate.of(2027, 4, 23),
                LocalDate.of(2026, 12, 12),
                LocalDate.of(2026, 12, 5),
                LocalDate.of(2026, 11, 28),
                LocalDate.of(2026, 11, 27),
                LocalDate.of(2026, 11, 25),
                LocalDate.of(2026, 11, 22),
                LocalDate.of(2026, 11, 21),
                LocalDate.of(2026, 11, 19)
            )
    }

    @Test
    fun `scrape reads the cancellation the venue writes into the title`() {
        val cancelled = scrapeBoundary().single { it.sourceId.endsWith("som-berlin-urban-spree") }

        cancelled.status shouldBe EventStatus.CANCELLED.name
        // The "CANCELLED - " marker and the venue tail are both stripped from the stored title.
        cancelled.title shouldBe "SOM"
    }

    @Test
    fun `scrape imports an odd start time verbatim rather than second-guessing it`() {
        val event = scrapeBoundary().single { it.eventDate == LocalDate.of(2026, 8, 8) }

        event.startTime shouldBe LocalTime.of(16, 34)
        event.pricePresale shouldBe BigDecimal("30.00")
        event.free shouldBe false
        // The venue keeps the poster's original filename; the spaces in it must be encoded
        // so the stored URL parses.
        event.imageUrl shouldBe "https://www.urbanspree.com/assets/project/urbanspree/mediasource/FLUXO%20invites%20EBONY.jpeg"
    }

    @Test
    fun `scrape flags a card whose price slot reads Free`() {
        val html =
            """
            <html><head><base href="https://www.urbanspree.com/"></head><body>
              <div id="pdopage">
                <a class="card" href="program/concerts/gratis.html" data-dateStart="2026-09-25 21:00:00">
                  <p class="card-text cat">Concerts</p><p class="card-text title">Gratis Show</p>
                  <ul><li class="list-group-item price">Free</li></ul>
                </a>
              </div>
            </body></html>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, listingUrl), listingUrl).single()

        event.free shouldBe true
        event.pricePresale.shouldBeNull()
    }

    @Test
    fun `scrape strips the venue and city tail in each of its spellings`() {
        val titles = (scrapeListing() + scrapeBoundary()).associate { it.sourceId to it.title }

        titles["urban_spree:concerts/coilguns-berlin-urban-spree"] shouldBe "Coilguns"
        titles["urban_spree:concerts/banda-entopica-urban-spree-berlin"] shouldBe "Banda Entopica"
        titles["urban_spree:concerts/múr-berlin"] shouldBe "MÚR"
        // A title with no venue tail is left alone.
        titles["urban_spree:concerts/fluxo-invites-ebony"] shouldBe "FLUXO INVITES EBONY"
    }

    @Test
    fun `scrape ignores a card with no usable date`() {
        val html =
            """
            <html><head><base href="https://www.urbanspree.com/"></head><body>
              <div id="pdopage">
                <a class="card" href="program/concerts/dated.html" data-dateStart="2026-09-01 20:00:00"
                   data-imgfeat="assets/a.jpg">
                  <p class="card-text cat">Concerts</p><p class="card-text title">Dated</p>
                </a>
                <a class="card" href="program/concerts/undated.html" data-dateStart="">
                  <p class="card-text cat">Concerts</p><p class="card-text title">Undated</p>
                </a>
              </div>
            </body></html>
            """.trimIndent()

        val events = scraper.scrape(Jsoup.parse(html, listingUrl), listingUrl)

        events shouldHaveSize 1
        events.single().title shouldBe "Dated"
    }

    @Test
    fun `scrape returns an empty list for a listing with no cards`() {
        val html = """<html><body><div id="pdopage"><p>No events</p></div></body></html>"""

        scraper.scrape(Jsoup.parse(html, listingUrl), listingUrl) shouldHaveSize 0
    }

    @Test
    fun `scrape drops a workshop card, by its category or by its title`() {
        val html =
            """
            <html><head><base href="https://www.urbanspree.com/"></head><body>
              <div id="pdopage">
                <a class="card" href="program/workshops/stencil.html" data-dateStart="2026-09-01 18:00:00">
                  <p class="card-text cat">Workshops</p><p class="card-text title">Stencil Basics</p>
                </a>
                <a class="card" href="program/events/workshop-by-key-clef.html" data-dateStart="2026-10-08 17:00:00">
                  <p class="card-text cat">Events</p><p class="card-text title">WORKSHOP by Key Clef - Mental Continuum</p>
                </a>
              </div>
            </body></html>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, listingUrl), listingUrl) shouldBe emptyList()
    }

    @Test
    fun `scrape leaves an unmapped category to the persistence default`() {
        val html =
            """
            <html><head><base href="https://www.urbanspree.com/"></head><body>
              <div id="pdopage">
                <a class="card" href="program/talks/artist-talk.html" data-dateStart="2026-09-01 18:00:00">
                  <p class="card-text cat">Talks</p><p class="card-text title">Artist Talk</p>
                </a>
              </div>
            </body></html>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, listingUrl), listingUrl).single()

        // Null (not OTHER) so a detail-page category can still win during the merge.
        event.eventType.shouldBeNull()
        event.toEventEntity(venueId = 1, venueSlug = "urban-spree", eventSourceId = 1).eventType shouldBe EventType.OTHER.name
    }
}
