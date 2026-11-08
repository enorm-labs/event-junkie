package de.norm.events.scraper.barjedervernunft

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [BarJederVernunftOverviewPageScraper], parsing a saved snapshot of the
 * `/de/programm/kalender.html` calendar page.
 */
class BarJederVernunftOverviewPageScraperTest {
    private val scraper = BarJederVernunftOverviewPageScraper()
    private val sourceUrl = "https://www.bar-jeder-vernunft.de/de/programm/kalender.html"

    private val events by lazy { scraper.scrape(fixtureDocument("barjedervernunft/barjedervernunft-overview.html", sourceUrl)) }

    @Test
    fun `scrape extracts one event per performance date`() {
        events shouldHaveSize 28
    }

    @Test
    fun `scrape maps all calendar fields of the first event`() {
        val event = events.first()

        event.title shouldBe "Oh What A Night!"
        event.subtitle shouldBe "Die mitreißende Liveshow mit Hits von Grease bis Dirty Dancing"
        event.eventDate shouldBe LocalDate.of(2026, 7, 31)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.doorsTime shouldBe null
        event.sourceUrl shouldBe
            "https://www.bar-jeder-vernunft.de/de/programm/programmuebersicht/oh-what-a-night-frankie-valli-show.html"
        event.sourceId shouldBe "bar_jeder_vernunft:2026-07-31-oh-what-a-night-frankie-valli-show"
        event.ticketUrl shouldBe "https://tickets.bar-jeder-vernunft.de/webshop/webticket/shop?event=4001&language=de"
        event.imageUrl.shouldNotBeNull() shouldContain "show_berlin_oh-what-a-night-frankie-valli-links-1204x630.jpg"
        event.soldOut shouldBe false
        event.status shouldBe "SCHEDULED"
    }

    @Test
    fun `scrape decodes HTML entities the CMS leaves escaped in the JSON-LD teaser`() {
        val description = events.first().description.shouldNotBeNull()

        description shouldContain "Frankie Valli & The Four"
        description shouldNotContain "&amp;"
    }

    @Test
    fun `scrape leaves show-page fields unset - they are merged in by the importer`() {
        val event = events.first()

        event.eventType shouldBe null
        event.genre shouldBe null
        event.pricePresale shouldBe null
        event.priceNote shouldBe null
        // The lineup is derived from the event type, which is only known once the genre is read.
        event.artists.shouldHaveSize(0)
    }

    @Test
    fun `scrape reads the year and start time from the JSON-LD, not the year-less date block`() {
        // The calendar renders "Fr 31.7." with the year only in a sticky month header, and the
        // time as "19 Uhr" / "20 Uhr" — both unusable; the JSON-LD startDate carries both.
        val earlyShow = events.first { it.eventDate == LocalDate.of(2026, 8, 2) }

        earlyShow.startTime shouldBe LocalTime.of(19, 0)
        earlyShow.eventDate.year shouldBe 2026
    }

    @Test
    fun `scrape gives each date of a residency its own sourceId while sharing the show URL`() {
        val residency = events.filter { it.title == "Oh What A Night!" }

        residency.size shouldBe 27
        residency.map { it.sourceUrl }.distinct() shouldHaveSize 1
        residency.map { it.sourceId }.distinct() shouldHaveSize residency.size
    }

    @Test
    fun `scrape reads the guest show billed inside the residency`() {
        val guest = events.single { it.title == "The Happy Disharmonists" }

        guest.eventDate shouldBe LocalDate.of(2026, 8, 24)
        guest.sourceId shouldBe "bar_jeder_vernunft:2026-08-24-the-happy-disharmonists-40-jahre"
    }

    @Test
    fun `scrape returns an empty list for a page without calendar cards`() {
        val document = Jsoup.parse("<html><body><div class='events-list'></div></body></html>", sourceUrl)

        scraper.scrape(document) shouldHaveSize 0
    }

    @Test
    fun `scrape skips a card whose JSON-LD sibling is missing rather than adopting the next card's`() {
        val document = Jsoup.parse(cardHtml(withJsonLd = false) + cardHtml(withJsonLd = true), sourceUrl)

        scraper.scrape(document).map { it.sourceId } shouldContainExactly
            listOf("bar_jeder_vernunft:2026-09-01-a-show")
    }

    @Test
    fun `scrape skips a card whose JSON-LD is malformed`() {
        val document = Jsoup.parse(cardHtml(withJsonLd = true, json = "{not json"), sourceUrl)

        scraper.scrape(document) shouldHaveSize 0
    }

    @Test
    fun `scrape flags a sold-out date from the schema org availability`() {
        val soldOutJson =
            """
            {"@type":"Event","name":"A Show","url":"https://www.bar-jeder-vernunft.de/de/programm/programmuebersicht/a-show.html",
             "startDate":"2026-09-01T20:00:00+0200","performer":"A Show",
             "offers":{"@type":"Offer","availability":"https://schema.org/SoldOut"}}
            """.trimIndent()
        val document = Jsoup.parse(cardHtml(withJsonLd = true, json = soldOutJson), sourceUrl)

        scraper.scrape(document).single().soldOut shouldBe true
    }

    /** A minimal calendar card, optionally followed by its JSON-LD sibling. */
    private fun cardHtml(
        withJsonLd: Boolean,
        json: String = DEFAULT_JSON_LD
    ): String {
        val card =
            """
            <div class="card card-type-calendar card-type-event">
                <div class="card-body"><div class="event-artist">A Show</div><div class="event-title">A sub-line</div></div>
            </div>
            """.trimIndent()
        return if (withJsonLd) card + """<script id="json-ld-data" type="application/ld+json">$json</script>""" else card
    }

    private companion object {
        val DEFAULT_JSON_LD =
            """
            {"@type":"Event","name":"A Show","url":"https://www.bar-jeder-vernunft.de/de/programm/programmuebersicht/a-show.html",
             "startDate":"2026-09-01T20:00:00+0200","performer":"A Show",
             "offers":{"@type":"Offer","availability":"https://schema.org/InStock"}}
            """.trimIndent()
    }
}
