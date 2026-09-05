package de.norm.events.scraper.morphine

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MorphineDetailPageScraper].
 *
 * Uses real detail-page snapshots: a fully-populated night with a sliding-scale price range, a
 * night selling advance tickets through PayPal whose pricing box holds a house rule rather than a
 * price, and a bare night stating a single door price with no image.
 */
class MorphineDetailPageScraperTest {
    private val scraper = MorphineDetailPageScraper()

    private fun parse(
        fixture: String,
        url: String
    ): ScrapedEvent? {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/morphine/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url), url)
    }

    @Test
    fun `parses all detail fields for a fully populated night`() {
        val url = "http://www.morphinerecords.com/events/sardy-fardy-live-recording"
        val event = parse("morphine-detail-simple.html", url).shouldNotBeNull()

        event.title shouldBe "Sardy Fardy - Live Recording"
        event.eventType shouldBe "CONCERT"
        event.eventDate shouldBe LocalDate.of(2026, 8, 7)
        event.doorsTime shouldBe LocalTime.of(20, 0)
        event.startTime shouldBe LocalTime.of(20, 30)
        event.sourceUrl shouldBe url
        event.sourceId shouldBe "morphine:sardy-fardy-live-recording"
        event.imageUrl shouldBe
            "http://www.morphinerecords.com/media/pages/events/sardy-fardy-live-recording/273308784-1784445631/design-42.png"
        event.description.shouldNotBeNull() shouldStartWith "After the remarkable success"
        event.status shouldBe "SCHEDULED"
        event.soldOut shouldBe false
        event.artists shouldBe listOf(ScrapedArtist("Sardy Fardy", "HEADLINER"))
    }

    @Test
    fun `keeps a sliding-scale range as a note without inventing a box-office price`() {
        val url = "http://www.morphinerecords.com/events/sardy-fardy-live-recording"
        val event = parse("morphine-detail-simple.html", url).shouldNotBeNull()

        event.priceNote shouldBe "Sliding scale 20- 25 Euro at The Door"
        event.priceBoxOffice shouldBe null
        event.pricePresale shouldBe null
        event.free shouldBe false
    }

    @Test
    fun `reads the presale price from the advance-ticket PayPal form`() {
        val url = "http://www.morphinerecords.com/events/all-about-birds-jon-rose-hinterland"
        val event = parse("morphine-detail-paypal.html", url).shouldNotBeNull()

        event.pricePresale shouldBe BigDecimal("15")
        // The form posts to PayPal instead of linking to a shop, so there is no ticket URL.
        event.ticketUrl shouldBe null
    }

    @Test
    fun `ignores a pricing box that carries a house rule rather than a price`() {
        val url = "http://www.morphinerecords.com/events/all-about-birds-jon-rose-hinterland"
        val event = parse("morphine-detail-paypal.html", url).shouldNotBeNull()

        // "Concert (two sets!) starts at 20:00 sharp. …" — prose, and its "20:00" must not
        // become a box-office price.
        event.priceNote shouldBe null
        event.priceBoxOffice shouldBe null
    }

    @Test
    fun `splits a co-billed lineup entry into two acts`() {
        val url = "http://www.morphinerecords.com/events/all-about-birds-jon-rose-hinterland"
        val event = parse("morphine-detail-paypal.html", url).shouldNotBeNull()

        event.artists shouldBe
            listOf(
                ScrapedArtist("ALL ABOUT BIRDS", "HEADLINER"),
                ScrapedArtist("JON ROSE: HINTERLAND!", "HEADLINER")
            )
        event.doorsTime shouldBe LocalTime.of(19, 30)
        event.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `joins several paragraph blocks and keeps their line breaks`() {
        val url = "http://www.morphinerecords.com/events/all-about-birds-jon-rose-hinterland"
        val event = parse("morphine-detail-paypal.html", url).shouldNotBeNull()

        // The instrument credits are <br>-separated lines within one paragraph.
        event.description.shouldNotBeNull() shouldContain "Jon Rose | violin & field recordings\nSusanne Fröhlich"
        // Later paragraph blocks are appended, separated by a blank line.
        event.description.shouldNotBeNull() shouldContain "The Potsdam–Berlin collective"
    }

    @Test
    fun `reads a single unambiguous door price as the box-office price`() {
        val url = "http://www.morphinerecords.com/events/neumann-schick-voglsinger"
        val event = parse("morphine-detail-door-price.html", url).shouldNotBeNull()

        event.priceNote shouldBe "10 Euro At The Door"
        event.priceBoxOffice shouldBe BigDecimal("10")
        event.pricePresale shouldBe null
        event.imageUrl shouldBe null
        // The performers come from the credit block, not from the surname-list title.
        event.artists.map { it.name } shouldBe listOf("Andrea Neumann", "Ignaz Schick", "Stefan Voglsinger")
    }

    @Test
    fun `bills the performers of an ensemble piece and keeps the work as the title`() {
        // "VINYL REDUCTION" is a turntable-quartet composition; the four players are credited below it (#1134).
        val url = "http://www.morphinerecords.com/events/vinyl-reduction-2"
        val event = parse("morphine-detail-performers.html", url).shouldNotBeNull()

        event.title shouldBe "VINYL REDUCTION - Day 2"
        event.artists shouldBe
            listOf(
                ScrapedArtist("Sofia Borges", "HEADLINER"),
                ScrapedArtist("Stefan Roigk", "HEADLINER"),
                ScrapedArtist("Ignaz Schick", "HEADLINER"),
                ScrapedArtist("Eliad Wagner", "HEADLINER")
            )
        event.doorsTime shouldBe LocalTime.of(20, 0)
        event.startTime shouldBe LocalTime.of(20, 30)
        event.priceNote shouldBe "10 - 20 Euro sliding scale"
    }

    @Test
    fun `returns null for a page without an event overlay`() {
        val url = "http://www.morphinerecords.com/events/missing"
        scraper.scrape(Jsoup.parse("<html><body></body></html>", url), url) shouldBe null
    }

    @Test
    fun `returns null for an overlay without a title`() {
        val url = "http://www.morphinerecords.com/events/missing"
        val html = """<html><body><section class="content overlay events"></section></body></html>"""
        scraper.scrape(Jsoup.parse(html, url), url) shouldBe null
    }

    @Test
    fun `ignores the listing repeated below the overlay`() {
        val url = "http://www.morphinerecords.com/events/neumann-schick-voglsinger"
        val event = parse("morphine-detail-door-price.html", url).shouldNotBeNull()

        // The page repeats the full /events listing as navigation; nothing from it may leak in.
        event.title shouldBe "Neumann Schick Voglsinger - Live Recording"
        event.eventDate shouldBe LocalDate.of(2026, 8, 31)
    }
}
