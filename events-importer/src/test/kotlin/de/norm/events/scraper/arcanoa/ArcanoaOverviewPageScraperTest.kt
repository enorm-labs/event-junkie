package de.norm.events.scraper.arcanoa

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [ArcanoaOverviewPageScraper].
 *
 * Parses the real `veranst.htm` snapshot (July–September) plus synthetic fragments for the
 * edge cases the live page does not currently show. The clock is pinned to 2026-07-20 —
 * before the fixture's earliest date (22.07.) — so weekday-based year inference is
 * deterministic. The scraper returns every dated entry as-is; dropping past-dated events is
 * the persistence layer's concern (`EventUpsertService`).
 */
class ArcanoaOverviewPageScraperTest {
    private val baseUrl = "https://www.ssi-media.com/arcanoa/veranst.htm"

    // 22 July 2026 is a Wednesday, matching the fixture's "Mi 22.07." line.
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC)
    private val scraper = ArcanoaOverviewPageScraper(clock)

    /**
     * Parses the snapshot from its bytes with no charset override, exactly as `HtmlFetcher`
     * does — the page declares `iso-8859-1` in a meta tag only, so Jsoup has to detect it.
     */
    private fun programme(): Document =
        Jsoup.parse(
            javaClass.classLoader.getResourceAsStream("scraper/arcanoa/arcanoa-overview.html")!!,
            null,
            baseUrl
        )

    @Nested
    inner class RealSnapshot {
        @Test
        fun `parses every dated entry from the three month blocks`() {
            val events = scraper.scrape(programme(), baseUrl)

            // 8 (July, from the 22nd) + 24 (August) + 23 (September), less the one private function.
            events shouldHaveSize 54
            events.map { it.eventDate }.distinct() shouldHaveSize 54
        }

        @Test
        fun `maps a plain concert line onto title, subtitle and headliner`() {
            val event = scraper.scrape(programme(), baseUrl).single { it.eventDate == LocalDate.of(2026, 8, 15) }

            event.title shouldBe "Jesse Cotton Stone"
            // The style tail stays a subtitle — "HellCountryBlues" would be a junk genre tag.
            event.subtitle shouldBe "HellCountryBlues"
            event.genre.shouldBeNull()
            event.eventType shouldBe EventType.CONCERT.name
            // The page publishes one time for the whole month block ("Veranstaltungsbeginn: 20 Uhr").
            event.startTime shouldBe LocalTime.of(20, 0)
            event.doorsTime.shouldBeNull()
            // No per-event pages, images, tickets or prices exist on this site.
            event.sourceUrl shouldBe baseUrl
            event.imageUrl.shouldBeNull()
            event.ticketUrl.shouldBeNull()
            event.sourceId shouldBe "arcanoa:2026-08-15-jesse-cotton-stone"
            event.artists.map { it.name } shouldContainExactly listOf("Jesse Cotton Stone")
        }

        @Test
        fun `keeps a hyphenated name whole and splits only at the spaced dash`() {
            val event = scraper.scrape(programme(), baseUrl).single { it.eventDate == LocalDate.of(2026, 8, 5) }

            event.title shouldBe "Mittelalter-Irish Folk"
            event.subtitle shouldBe "freie Bühne - SpielleuteSession"
        }

        @Test
        fun `splits a co-billed line into one artist per act`() {
            val event = scraper.scrape(programme(), baseUrl).single { it.eventDate == LocalDate.of(2026, 8, 29) }

            event.title shouldBe "Grizzly & the Duck of Death + Fred Barolo"
            event.subtitle.shouldBeNull()
            // The "& the …" backing-band tail stays attached; only the "+" co-bill splits.
            event.artists.map { it.name } shouldContainExactly listOf("Grizzly & the Duck of Death", "Fred Barolo")
        }

        @Test
        fun `mints no artist for the venue's recurring formats`() {
            val events = scraper.scrape(programme(), baseUrl)

            // Monday open stage, Wednesday medieval session, Tuesday jam — programmes, not performers.
            events.single { it.eventDate == LocalDate.of(2026, 8, 3) }.artists.shouldHaveSize(0)
            events.single { it.eventDate == LocalDate.of(2026, 9, 16) }.artists.shouldHaveSize(0)
            events.single { it.eventDate == LocalDate.of(2026, 9, 8) }.artists.shouldHaveSize(0)
        }

        @Test
        fun `titles a labelled programme line by its label, not the whole blurb`() {
            val event = scraper.scrape(programme(), baseUrl).single { it.eventDate == LocalDate.of(2026, 8, 4) }

            event.title shouldBe "JAM für Alle"
            event.subtitle shouldBe
                "19-21 Uhr: Songwriting workshop mit Dave Benjoya anschließend Karaoke - open stage"
        }

        @Test
        fun `decodes the Latin-1 page's umlauts`() {
            val event = scraper.scrape(programme(), baseUrl).single { it.eventDate == LocalDate.of(2026, 9, 18) }

            event.title shouldBe "Eddie & die Bäänd"
            event.artists.map { it.name } shouldContainExactly listOf("Eddie & die Bäänd")
        }

        @Test
        fun `skips the night booked as a private function`() {
            val events = scraper.scrape(programme(), baseUrl)

            // "Fr 25.09.Live: geschlossene Gesellschaft" — the venue is taken, but nothing is public.
            events.none { it.eventDate == LocalDate.of(2026, 9, 25) } shouldBe true
        }

        @Test
        fun `ignores the Mittelaltertreffen recap that repeats Wednesdays already listed`() {
            val events = scraper.scrape(programme(), baseUrl)

            // The recap lists 05/12/19/26 August a second time; each must appear exactly once.
            events.filter { it.eventDate.monthValue == 8 && it.eventDate.dayOfWeek.value == 3 } shouldHaveSize 4
        }
    }

    @Nested
    inner class EdgeCases {
        private fun fragment(programmeLines: String) =
            Jsoup.parse(
                """
                <table><tr><td>
                  <p><font class="gesperrt"><b>Oktober</b></font></p>
                  <p><font><b>Live Musik:</b><br>$programmeLines</font></p>
                  <p>Veranstaltungsbeginn: 20 Uhr</p>
                </td></tr></table>
                """.trimIndent(),
                baseUrl
            )

        @Test
        fun `returns nothing for a page with no month blocks`() {
            scraper
                .scrape(Jsoup.parse("<html><body><p>Keine Termine</p></body></html>", baseUrl), baseUrl)
                .shouldHaveSize(0)
        }

        @Test
        fun `returns nothing for a month block whose programme paragraph is missing`() {
            val document =
                Jsoup.parse(
                    """<table><tr><td><p><font class="gesperrt"><b>Oktober</b></font></p></td></tr></table>""",
                    baseUrl
                )

            scraper.scrape(document, baseUrl).shouldHaveSize(0)
        }

        @Test
        fun `skips an entry whose date does not exist`() {
            val events = scraper.scrape(fragment("Do 31.10.Live: Real Band<br>Fr 32.10.Live: Impossible Band"), baseUrl)

            events shouldHaveSize 1
            events.single().title shouldBe "Real Band"
        }

        @Test
        fun `handles an entry with no style tail and no Live label`() {
            val events = scraper.scrape(fragment("Sa 03.10. Solo Act"), baseUrl)

            val event = events.single()
            event.title shouldBe "Solo Act"
            event.subtitle.shouldBeNull()
            event.eventDate shouldBe LocalDate.of(2026, 10, 3)
        }

        @Test
        fun `skips a private function wherever the marker sits in the line`() {
            val page =
                fragment(
                    "Fr 16.10. Larp Taverne zur schwarzen Tatze - geschlossene Gesellschaft<br>" +
                        "Sa 17.10.Live: Real Band<br>" +
                        "Sa 19.12.Live: -- geschlossene Gesellschaft --"
                )

            scraper.scrape(page, baseUrl).map { it.title } shouldContainExactly listOf("Real Band")
        }

        @Test
        fun `strips a leading plus from the style tail`() {
            val events = scraper.scrape(fragment("Sa 03.10.Live: Some Band - + open stage"), baseUrl)

            events.single().subtitle shouldBe "open stage"
        }

        @Test
        fun `leaves startTime null when the month block publishes none`() {
            val document =
                Jsoup.parse(
                    """
                    <table><tr><td>
                      <p><font class="gesperrt"><b>Oktober</b></font></p>
                      <p><font><b>Live Musik:</b><br>Sa 03.10.Live: Some Band</font></p>
                    </td></tr></table>
                    """.trimIndent(),
                    baseUrl
                )

            scraper
                .scrape(document, baseUrl)
                .single()
                .startTime
                .shouldBeNull()
        }

        @Test
        fun `reads a start time given with minutes`() {
            val document =
                Jsoup.parse(
                    """
                    <table><tr><td>
                      <p><font class="gesperrt"><b>Oktober</b></font></p>
                      <p><font><b>Live Musik:</b><br>Sa 03.10.Live: Some Band</font></p>
                      <p>Veranstaltungsbeginn: 20.30 Uhr</p>
                    </td></tr></table>
                    """.trimIndent(),
                    baseUrl
                )

            scraper.scrape(document, baseUrl).single().startTime shouldBe LocalTime.of(20, 30)
        }

        @Test
        fun `types a party night from its title instead of defaulting to concert`() {
            val events = scraper.scrape(fragment("Sa 03.10.Live: Halloween Party - Disco"), baseUrl)

            val event = events.single()
            event.eventType shouldBe EventType.PARTY.name
            // A party's title names the night, not a performer.
            event.artists.shouldHaveSize(0)
        }

        @Test
        fun `ignores a gesperrt heading that is not a month name`() {
            val document =
                Jsoup.parse(
                    """<table><tr><td><p><font class="gesperrt"><b>LINKS</b></font></p>
                       <p><font>Live Musik:<br>Sa 03.10.Live: Some Band</font></p></td></tr></table>""",
                    baseUrl
                )

            scraper.scrape(document, baseUrl).shouldHaveSize(0)
        }
    }
}
