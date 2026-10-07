package de.norm.events.scraper.supamolly

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [SupamollyOverviewPageScraper].
 *
 * Uses saved snapshots of the real Supamolly programme page as regression fixtures: September
 * 2026 bills the café social with its time in the name, October 2026 without. Every row id
 * carries a full `YYYYMMDDHHMM` stamp, so no clock injection is needed for date inference.
 */
class SupamollyOverviewPageScraperTest {
    private val baseUrl = "https://www.supamolly.de/?p=programm"
    private val scraper = SupamollyOverviewPageScraper()

    private fun scrape(fixture: String = SEPTEMBER): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/supamolly/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun eventWithStamp(
        stamp: String,
        fixture: String = SEPTEMBER
    ) = scrape(fixture).first { it.sourceId == "supamolly:$stamp" }

    @Test
    fun `scrape extracts every event row except the monthly programme posters`() {
        // 10 rows carry an event id; two of them announce the month's printed programme.
        scrape() shouldHaveSize 8
    }

    @Test
    fun `scrape extracts every row of the current page except its programme poster`() {
        // 11 rows carry an event id; one announces the month's printed programme.
        scrape(OCTOBER) shouldHaveSize 10
    }

    @Nested
    inner class ConcertParsing {
        @Test
        fun `parses a fully populated concert`() {
            val event = eventWithStamp("202609112130")

            event.title shouldBe "Edelfaul 7th Anniversary, DIKDaeDØR, Shoee, Celine Demon, Coasta"
            event.eventType shouldBe "CONCERT"
            event.eventDate shouldBe LocalDate.of(2026, 9, 11)
            event.startTime shouldBe LocalTime.of(21, 30)
            event.sourceUrl shouldBe "https://www.supamolly.de/?p=programm#202609112130"
            event.sourceId shouldBe "supamolly:202609112130"
            event.status shouldBe "SCHEDULED"
            event.soldOut shouldBe false
            event.free shouldBe false
        }

        @Test
        fun `bills the first act as headliner and the rest as support`() {
            eventWithStamp("202609192130").artists shouldContainExactly
                listOf(
                    ScrapedArtist("Headbutt", "HEADLINER"),
                    ScrapedArtist("Banana Of Death", "SUPPORT"),
                    ScrapedArtist("Moloch", "SUPPORT")
                )
        }

        @Test
        fun `resolves the full-size flyer from the thumbnail path`() {
            eventWithStamp("202609112130").imageUrl shouldBe "https://www.supamolly.de/flyer/202609112130.jpg"
        }

        @Test
        fun `leaves the flyer null for a row without one`() {
            eventWithStamp("202609182130").imageUrl.shouldBeNull()
        }

        @Test
        fun `keeps a band name whose conjunction is a backing-act tail intact`() {
            eventWithStamp("202609262130").artists shouldContainExactly
                listOf(
                    ScrapedArtist("Feo & Friends", "HEADLINER"),
                    ScrapedArtist("La Mula Santa", "SUPPORT")
                )
        }

        @Test
        fun `ignores extra reference-link blocks that bill no act`() {
            // The 03.09 row holds three `.even` blocks: one act plus two bare link blocks.
            val event = eventWithStamp("202609032100")

            event.title shouldBe "Skarface est.1991 La France"
            event.artists shouldContainExactly listOf(ScrapedArtist("Skarface est.1991 La France", "HEADLINER"))
        }

        @Test
        fun `drops a bare support placeholder from the lineup but keeps it in the title`() {
            val event = eventWithStamp("202609182130")

            event.title shouldBe "Monde de Merde, & Support"
            event.artists shouldContainExactly listOf(ScrapedArtist("Monde de Merde", "HEADLINER"))
        }

        @Test
        fun `leaves prices and ticket url unset since the venue publishes neither`() {
            val event = eventWithStamp("202609112130")

            event.pricePresale.shouldBeNull()
            event.priceBoxOffice.shouldBeNull()
            event.priceNote.shouldBeNull()
            event.ticketUrl.shouldBeNull()
        }

        @Test
        fun `reads the genre from the acts' style notes, in billing order`() {
            val document =
                Jsoup.parse(
                    """<table><tr class="event" id="202610102130"><td class="evcont">
                       <div class="even"><div class="tit"><b>Circus Rhapsody</b></div><div class="beschr">Folk Punk</div></div>
                       <div class="even"><div class="tit"><b>FONA</b></div><div class="beschr">Emo / Alternative</div></div>
                       <div class="even"><div class="tit"><b>Missstand</b></div><div class="beschr">Punk Rock</div></div>
                       </td></tr></table>""",
                    baseUrl
                )

            scraper.scrape(document, baseUrl).single().genre shouldBe "Folk, Punk, Emo, Alternative"
        }

        @Test
        fun `keeps the notes as the description when they carry a style`() {
            val document =
                Jsoup.parse(
                    """<table><tr class="event" id="202610102130"><td class="evcont">
                       <div class="even"><div class="tit"><b>Missstand</b></div><div class="beschr">Punk Rock</div></div>
                       </td></tr></table>""",
                    baseUrl
                )

            scraper.scrape(document, baseUrl).single().description shouldBe "Punk Rock"
        }

        @Test
        fun `leaves the genre unset when no act has a note`() {
            eventWithStamp("202609112130").genre.shouldBeNull()
        }

        @Test
        fun `excludes the reference urls from the description`() {
            // Every `.even` block on this row carries a Bandcamp/Instagram link and no note.
            eventWithStamp("202609112130").description.shouldBeNull()
        }
    }

    @Nested
    inner class NonConcertParsing {
        @Test
        fun `types an artist-less service night as OTHER rather than a concert`() {
            val event = eventWithStamp("202609061530")

            event.title shouldBe "Kuchen & Kaffee 15:30 Uhr"
            event.eventType shouldBe "OTHER"
            event.eventDate shouldBe LocalDate.of(2026, 9, 6)
            event.startTime shouldBe LocalTime.of(15, 30)
        }

        @Test
        fun `never mints a schedule note as an artist`() {
            eventWithStamp("202609061530").artists.shouldBeEmpty()
        }

        @Test
        fun `keeps the accompanying note as the description`() {
            eventWithStamp("202609061530").description shouldContain "Pizza (Alles auch vegan)"
        }

        @Test
        fun `reads no genre from a prose note`() {
            eventWithStamp("202609061530").genre.shouldBeNull()
        }

        @Test
        fun `types the cafe social billed without a time as OTHER`() {
            val event = eventWithStamp("202610041530", OCTOBER)

            event.title shouldBe "Kuchen & Kaffee"
            event.eventType shouldBe "OTHER"
            event.eventDate shouldBe LocalDate.of(2026, 10, 4)
            event.startTime shouldBe LocalTime.of(15, 30)
        }

        @Test
        fun `never mints the cafe social billed without a time as an artist`() {
            eventWithStamp("202610041530", OCTOBER).artists.shouldBeEmpty()
        }

        @Test
        fun `keeps the cafe social's note as the description and reads no genre from it`() {
            val event = eventWithStamp("202610041530", OCTOBER)

            event.description shouldBe "Kaffee & Kuchen ab 19:30 Pizza (alles auch vegan)"
            event.genre.shouldBeNull()
        }

        @Test
        fun `recognises the cafe social in either word order`() {
            val document =
                Jsoup.parse(
                    """<table><tr class="event" id="202610111530"><td class="evcont">
                       <div class="even"><div class="tit"><b>Kaffee und Kuchen</b></div></div></td></tr></table>""",
                    baseUrl
                )

            val event = scraper.scrape(document, baseUrl).single()

            event.eventType shouldBe "OTHER"
            event.artists.shouldBeEmpty()
        }

        @Test
        fun `keeps an act whose name only contains the cafe words`() {
            val document =
                Jsoup.parse(
                    """<table><tr class="event" id="202610112130"><td class="evcont">
                       <div class="even"><div class="tit"><b>Kaffee & Kuchen Kollektiv</b></div></div></td></tr></table>""",
                    baseUrl
                )

            scraper.scrape(document, baseUrl).single().artists shouldContainExactly
                listOf(ScrapedArtist("Kaffee & Kuchen Kollektiv", "HEADLINER"))
        }

        @Test
        fun `skips the monthly programme poster rows`() {
            val stamps = scrape().map { it.sourceId }

            stamps.contains("supamolly:202609022359") shouldBe false
            stamps.contains("supamolly:202609172359") shouldBe false
            scrape(OCTOBER).map { it.sourceId }.contains("supamolly:202610022359") shouldBe false
        }

        @Test
        fun `skips a poster row whatever follows the month name`() {
            val document =
                Jsoup.parse(
                    """<table><tr class="event" id="202610082359"><td class="evcont"><div class="even">
                       <div class="tit"><b>Oktober Supamolly<div class="progln" alt=""> </div></b></div>
                       <div class="beschr  empty"><div class="lin"><div></div></div></div></div></td></tr></table>""",
                    baseUrl
                )

            scraper.scrape(document, baseUrl) shouldBe emptyList()
        }

        @Test
        fun `keeps a night named after a month at its real time`() {
            val document =
                Jsoup.parse(
                    """<table><tr class="event" id="202610172130"><td class="evcont">
                       <div class="even"><div class="tit"><b>Oktober Supamolly</b></div></div></td></tr></table>""",
                    baseUrl
                )

            scraper.scrape(document, baseUrl).single().title shouldBe "Oktober Supamolly"
        }
    }

    @Nested
    inner class MalformedInput {
        @Test
        fun `returns an empty list for a page without event rows`() {
            val document = Jsoup.parse("<html><body><table></table></body></html>", baseUrl)

            scraper.scrape(document, baseUrl).shouldBeEmpty()
        }

        @Test
        fun `skips a row whose id is not a date stamp`() {
            val document =
                Jsoup.parse(
                    """<table><tr class="event" id="latest"><td class="evcont">
                       <div class="even"><div class="tit"><b>Some Band</b></div></div></td></tr></table>""",
                    baseUrl
                )

            scraper.scrape(document, baseUrl).shouldBeEmpty()
        }

        @Test
        fun `skips a row whose stamp is not a real calendar date`() {
            val document =
                Jsoup.parse(
                    """<table><tr class="event" id="202613352130"><td class="evcont">
                       <div class="even"><div class="tit"><b>Some Band</b></div></div></td></tr></table>""",
                    baseUrl
                )

            scraper.scrape(document, baseUrl).shouldBeEmpty()
        }

        @Test
        fun `skips a row that bills no act`() {
            val document =
                Jsoup.parse(
                    """<table><tr class="event" id="202609112130"><td class="date">
                       <div class="uhr">21:30</div></td><td class="evcont"></td></tr></table>""",
                    baseUrl
                )

            scraper.scrape(document, baseUrl).shouldBeEmpty()
        }

        @Test
        fun `falls back to the stamp time when the displayed time is missing`() {
            val document =
                Jsoup.parse(
                    """<table><tr class="event" id="202609112130"><td class="date"></td><td class="evcont">
                       <div class="even"><div class="tit"><b>Some Band</b></div></div></td></tr></table>""",
                    baseUrl
                )

            scraper.scrape(document, baseUrl).single().startTime shouldBe LocalTime.of(21, 30)
        }
    }

    private companion object {
        /** The café social billed as "Kuchen & Kaffee 15:30 Uhr", time in the name. */
        const val SEPTEMBER = "supamolly-overview-2026-09.html"

        /** The café social billed as "Kuchen & Kaffee", time only in the date cell. */
        const val OCTOBER = "supamolly-overview-2026-10.html"
    }
}
