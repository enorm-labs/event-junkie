package de.norm.events.scraper.aeden

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [AedenOverviewPageScraper].
 *
 * Parses the saved July and August 2026 month snapshots. Every `.event-date` cell carries a
 * four-digit year, so no clock is needed — the dates are deterministic.
 */
class AedenOverviewPageScraperTest {
    private val scraper = AedenOverviewPageScraper()

    private val julyUrl = "https://aedenberlin.com/month/?month=2026-07"
    private val augustUrl = "https://aedenberlin.com/month/?month=2026-08"

    private fun monthPage(
        name: String,
        baseUrl: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/aeden/$name")!!
            .bufferedReader()
            .readText(),
        baseUrl
    )

    private val julyEvents by lazy { scraper.scrape(monthPage("aeden-month-2026-07.html", julyUrl), julyUrl) }
    private val augustEvents by lazy { scraper.scrape(monthPage("aeden-month-2026-08.html", augustUrl), augustUrl) }

    @Test
    fun `parses every dated night on a month page`() {
        julyEvents shouldHaveSize 10
        augustEvents shouldHaveSize 4
    }

    @Test
    fun `extracts all fields of a fully populated night`() {
        val event = augustEvents.first { it.eventDate == LocalDate.of(2026, 8, 1) }

        event.title shouldBe "silikon"
        event.eventType shouldBe EventType.PARTY.name
        event.startTime shouldBe LocalTime.of(23, 0)
        event.genre shouldBe "Techno"
        event.description!! shouldContain "all-FLINTA* lineup"
        event.imageUrl shouldBe "https://aedenberlin.com/wp-content/uploads/2026/06/silikon-cover-for-events-1024x1024.jpg"
        event.ticketUrl shouldBe "https://ra.co/events/2461806"
        // The month page links no per-event page, so the month URL is the source URL and the
        // identity is built from the date plus the slugified title.
        event.sourceUrl shouldBe augustUrl
        event.sourceId shouldBe "aeden:2026-08-01-silikon"
        // No prices anywhere on the ÆDEN month pages.
        event.pricePresale.shouldBeNull()
        event.priceBoxOffice.shouldBeNull()
        event.priceNote.shouldBeNull()
    }

    @Test
    fun `splits the Lineup paragraph into DJs one per line`() {
        val event = augustEvents.first { it.eventDate == LocalDate.of(2026, 8, 1) }

        event.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Alexa Fluor", role = "DJ"),
                ScrapedArtist(name = "ALIS.", role = "DJ"),
                ScrapedArtist(name = "DJ Gianni", role = "DJ"),
                ScrapedArtist(name = "ELOISA", role = "DJ"),
                ScrapedArtist(name = "Melanchromie", role = "DJ"),
                ScrapedArtist(name = "Razzle Dazzler", role = "DJ"),
                ScrapedArtist(name = "NASTYA NVRSLP", role = "DJ"),
                ScrapedArtist(name = "YENKOV", role = "DJ")
            )
    }

    @Test
    fun `strips the decorative ampersand and drops unannounced acts from a lineup`() {
        val event = julyEvents.first { it.eventDate == LocalDate.of(2026, 7, 15) && it.title == "LILITH" }

        // "&#038; SECRET ACT" is a decorative conjunction plus an unnamed slot — the "&" is stripped
        // and "SECRET ACT" is dropped rather than minted as an artist. A b2b slot is two DJs (#1844).
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "DANA NADA", role = "DJ"),
                ScrapedArtist(name = "STEEZY", role = "DJ"),
                ScrapedArtist(name = "KIV", role = "DJ"),
                ScrapedArtist(name = "YANES", role = "DJ")
            )
    }

    @Test
    fun `extracts no artists when the lineup is still to be announced`() {
        val event = julyEvents.first { it.eventDate == LocalDate.of(2026, 7, 22) }

        event.title shouldBe "LILITH"
        // The whole roster is "Lineup: TBA soon..".
        event.artists.shouldBeEmpty()
        event.description!! shouldContain "TBA"
    }

    @Test
    fun `extracts no artists when the blurb carries no Lineup paragraph`() {
        val event = julyEvents.first { it.eventDate == LocalDate.of(2026, 7, 17) && it.title.startsWith("CHROMA") }

        event.description shouldBe "More info coming soon.."
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `leaves the genre null when the venue tagged none`() {
        val event = julyEvents.first { it.title.startsWith("Bleach Berlin") }

        event.eventDate shouldBe LocalDate.of(2026, 7, 17)
        event.genre.shouldBeNull()
        event.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `keeps a multi-style genre list raw`() {
        val event = julyEvents.first { it.title.contains("GROOVE STREET") }

        event.genre shouldBe "Techno, House"
        event.sourceId shouldBe "aeden:2026-07-31-groove-street"
    }

    @Test
    fun `returns no events for a page without event blocks`() {
        val empty = Jsoup.parse("<html><body><main><section></section></main></body></html>", julyUrl)

        scraper.scrape(empty, julyUrl).shouldBeEmpty()
    }

    @Test
    fun `skips a block whose date cannot be parsed`() {
        val malformed =
            Jsoup.parse(
                """
                <html><body><div class="single-accordion">
                    <div class="event-title"><h3>Broken Night</h3></div>
                    <div class="event-date"><h3>soon</h3></div>
                </div></body></html>
                """.trimIndent(),
                julyUrl
            )

        scraper.scrape(malformed, julyUrl).shouldBeEmpty()
    }

    // Production stored `Octave?` as a DJ (#1843).
    @Test
    fun `drops the question mark after an unconfirmed booking`() {
        val page =
            Jsoup.parse(
                """
                <html><body><div class="single-accordion">
                    <div class="event-title"><h3>INTERSTICE</h3></div>
                    <div class="event-date"><h3>01/08/2026 Saturday</h3></div>
                    <div class="event-lineup"><p>Lineup:<br>Complice<br>Octave?</p></div>
                </div></body></html>
                """.trimIndent(),
                julyUrl
            )

        scraper
            .scrape(page, julyUrl)
            .single()
            .artists
            .map { it.name } shouldContainExactly listOf("Complice", "Octave")
    }

    @Test
    fun `skips a block with no title`() {
        val untitled =
            Jsoup.parse(
                """
                <html><body><div class="single-accordion">
                    <div class="event-date"><h3>01/08/2026 Saturday</h3></div>
                </div></body></html>
                """.trimIndent(),
                julyUrl
            )

        scraper.scrape(untitled, julyUrl).shouldBeEmpty()
    }
}
