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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [SupamollyOverviewPageScraper].
 *
 * Uses a saved snapshot of the real Supamolly programme page as a regression
 * fixture. Every row id carries a full `YYYYMMDDHHMM` stamp, so no clock injection
 * is needed for date inference.
 */
class SupamollyOverviewPageScraperTest {
    private val baseUrl = "https://www.supamolly.de/?p=programm"
    private val scraper = SupamollyOverviewPageScraper()
    private lateinit var html: String

    @BeforeEach
    fun setUp() {
        html =
            javaClass.classLoader
                .getResourceAsStream("scraper/supamolly/supamolly-overview.html")!!
                .bufferedReader()
                .readText()
    }

    private fun scrape(): List<ScrapedEvent> = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)

    private fun eventWithStamp(stamp: String) = scrape().first { it.sourceId == "supamolly:$stamp" }

    @Test
    fun `scrape extracts every event row except the monthly programme posters`() {
        // 10 rows carry an event id; two of them announce the month's printed programme.
        scrape() shouldHaveSize 8
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
        fun `skips the monthly programme poster rows`() {
            val stamps = scrape().map { it.sourceId }

            stamps.contains("supamolly:202609022359") shouldBe false
            stamps.contains("supamolly:202609172359") shouldBe false
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
}
