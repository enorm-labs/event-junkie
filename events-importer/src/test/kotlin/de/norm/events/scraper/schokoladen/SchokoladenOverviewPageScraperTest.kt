package de.norm.events.scraper.schokoladen

import de.norm.events.genretag.normalizeGenre
import de.norm.events.scraper.ScrapedArtist
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
 * Unit tests for [SchokoladenOverviewPageScraper].
 *
 * Uses a static HTML snapshot of the real Schokoladen Mitte homepage for
 * deterministic, offline-safe testing.
 */
class SchokoladenOverviewPageScraperTest {
    private val scraper = SchokoladenOverviewPageScraper()
    private val baseUrl = "https://www.schokoladen-mitte.de/"
    private lateinit var html: String

    @BeforeEach
    fun setUp() {
        html =
            javaClass.classLoader
                .getResourceAsStream("scraper/schokoladen/schokoladen-overview.html")!!
                .bufferedReader()
                .readText()
    }

    private fun scrape() = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)

    @Test
    fun `scrape extracts all events from fixture`() {
        scrape() shouldHaveSize 10
    }

    @Nested
    inner class Pagination {
        private fun load(name: String) =
            javaClass.classLoader
                .getResourceAsStream("scraper/schokoladen/$name")!!
                .bufferedReader()
                .readText()

        @Test
        fun `parses a later page in the next year`() {
            val url = "https://www.schokoladen-mitte.de/?page=8"
            val events = scraper.scrape(Jsoup.parse(load("schokoladen-overview-page-last.html"), url), url)

            events shouldHaveSize 8
            events.first().eventDate shouldBe LocalDate.of(2027, 2, 6)
        }
    }

    @Nested
    inner class ConcertParsing {
        @Test
        fun `parses a Musik concert with full data`() {
            val concert = scrape().first { it.sourceId == "schokoladen:e20260711" }

            concert.title shouldBe "MOLOCH (punk, bln) + PINK WONDER (scumpunk, bln)"
            concert.eventType shouldBe "CONCERT"
            concert.eventDate shouldBe LocalDate.of(2026, 7, 11)
            concert.doorsTime shouldBe LocalTime.of(19, 0)
            concert.startTime shouldBe LocalTime.of(20, 0)
            concert.sourceUrl shouldBe "https://www.schokoladen-mitte.de/#e20260711"
            concert.ticketUrl shouldBe "https://vvk.link/4g2kjio"
            concert.imageUrl shouldBe
                "https://www.schokoladen-mitte.de/media/images/3jbQKL49TtTIfkYKypZqNuwmESVzGAI4F0Sm0VVG.jpg"
            concert.subtitle shouldContain "after 22h"
            concert.description shouldContain "Punk in Moll"
            concert.promoters shouldContainExactly listOf("lose temper")
            concert.soldOut shouldBe false
            // The "(genre, origin)" annotations name the genres; "bln" is an origin, not a genre (#1495).
            concert.genre shouldBe "punk, scumpunk"
        }

        @Test
        fun `reads the sold-out banner and the title genres of a sold-out show`() {
            // 17 September 2026 (#1495): the banner is the subtitle and the last description paragraph.
            val autumn =
                javaClass.classLoader
                    .getResourceAsStream("scraper/schokoladen/schokoladen-overview-sold-out.html")!!
                    .bufferedReader()
                    .readText()
            val events = scraper.scrape(Jsoup.parse(autumn, baseUrl), baseUrl)
            val rabbits = events.first { it.sourceId == "schokoladen:e20260917" }

            rabbits.title shouldBe "1000 Rabbits (art-pop, uk) + Lande Hekt (indie-pop/songwriter, uk)"
            rabbits.soldOut shouldBe true
            // The page keeps its shop button; its own words are "10 Tickets on the Doors".
            rabbits.ticketUrl.shouldBeNull()
            rabbits.genre shouldBe "art-pop, indie-pop/songwriter"
            // What the shared normalizer makes of them is its own call; this pins the hand-over.
            normalizeGenre(rabbits.genre) shouldContainExactly listOf("Art-pop", "Indie", "Songwriter")

            // Lothario's bio says a tape "sold out in a record 3 months"; that is prose, not the banner.
            val lothario = events.first { it.title.startsWith("Lothario") }
            lothario.soldOut shouldBe false
            lothario.ticketUrl shouldBe "https://vvk.link/4hkv9w0"
        }

        @Test
        fun `keeps an origin out of the genres whichever way the venue wrote the annotation`() {
            fun genreOf(title: String): String? =
                scraper
                    .scrape(
                        Jsoup.parse(
                            """
                            <div class="event"><h6 class="category">Musik</h6><h2 class="fw-bold">$title</h2>
                              <div class="event-info" id="e20260101" data-event-date="2026-01-01"></div></div>
                            """.trimIndent(),
                            baseUrl
                        ),
                        baseUrl
                    ).single()
                    .genre

            genreOf("CÚT LỘN (Ho Chi Minh City, hardcore punk) + I HATE THE BOUNCERS (Bln, punk)") shouldBe "hardcore punk, punk"
            genreOf("OCCRASY (SWE) + Social Torsten (Alternative-Punkrock/ Bernau)") shouldBe "Alternative-Punkrock"
            genreOf("BELAKO (Post-Punk/New-Wave, Basque Country) + Ghosts of Berlin (neo grunge)") shouldBe "Post-Punk/New-Wave, neo grunge"
            genreOf("Karaoke Night w/ KJ Der Käpt'n").shouldBeNull()
        }

        @Test
        fun `derives headliner artists from co-billed title, stripping genre annotations`() {
            val concert = scrape().first { it.sourceId == "schokoladen:e20260711" }

            concert.artists shouldContainExactly
                listOf(
                    ScrapedArtist(name = "MOLOCH", role = "HEADLINER", titleDerived = true),
                    ScrapedArtist(name = "PINK WONDER", role = "HEADLINER", titleDerived = true)
                )
        }

        @Test
        fun `strips a set-count note from a title-derived headliner`() {
            val event = scrape().first { it.sourceId == "schokoladen:e20260715" }
            event.title shouldBe "Toshìn & The Teleporters - 2 Sets!"
            event.artists shouldContainExactly listOf(ScrapedArtist(name = "Toshìn & The Teleporters", role = "HEADLINER", titleDerived = true))
        }

        @Test
        fun `extracts no artist from an anniversary Hoffest title`() {
            val hoffest = scrape().first { it.sourceId == "schokoladen:e20260717" }
            hoffest.title shouldBe "36 Jahre Schokoladen - Hoffest"
            hoffest.artists.shouldBeEmpty()
        }

        @Test
        fun `parses times given in the German Einlass and h-suffixed form`() {
            // "36 Jahre Schokoladen - Hoffest" lists only "Einlass: 18:30 Uhr" — a doors time, no show time.
            val hoffest = scrape().first { it.sourceId == "schokoladen:e20260717" }
            hoffest.doorsTime shouldBe LocalTime.of(18, 30)
            hoffest.startTime.shouldBeNull()

            // "LSD" lists "Einlass 19h Beginn 20h" — h-suffixed doors and show.
            val lsd = scrape().first { it.sourceId == "schokoladen:e20260721" }
            lsd.doorsTime shouldBe LocalTime.of(19, 0)
            lsd.startTime shouldBe LocalTime.of(20, 0)
        }

        @Test
        fun `reads times written before their labels, with Konzert as the start`() {
            // Revolte Tanzbein, 5 September 2026: "19:00 Einlass - 20:00 Konzert - 22:00 DJ-Set" (#1141).
            val autumn =
                javaClass.classLoader
                    .getResourceAsStream("scraper/schokoladen/schokoladen-overview-time-line.html")!!
                    .bufferedReader()
                    .readText()
            val revolte = scraper.scrape(Jsoup.parse(autumn, baseUrl), baseUrl).first { it.sourceId == "schokoladen:e20260905" }
            revolte.doorsTime shouldBe LocalTime.of(19, 0)
            revolte.startTime shouldBe LocalTime.of(20, 0)
        }
    }

    @Nested
    inner class OtherCategories {
        @Test
        fun `maps a Lesung category to READING and extracts no artists`() {
            val reading = scrape().first { it.sourceId == "schokoladen:e20260721" }

            reading.title shouldBe "LSD - liebe statt drogen"
            reading.eventType shouldBe "READING"
            reading.promoters shouldContainExactly listOf("die lesebühne")
            reading.artists.shouldBeEmpty()
        }

        @Test
        fun `leaves a Spezial category unmapped so it defaults to OTHER downstream`() {
            val special = scrape().first { it.sourceId == "schokoladen:e20260724" }

            special.title shouldContain "SHEENA IS"
            special.eventType.shouldBeNull()
            special.ticketUrl.shouldBeNull()
            special.artists.shouldBeEmpty()
        }
    }

    @Nested
    inner class Robustness {
        @Test
        fun `strips a trailing presents flourish from the promoter`() {
            val event = scrape().first { it.sourceId == "schokoladen:e20260723" }
            event.promoters shouldContainExactly listOf("little league shows")
        }

        @Test
        fun `splits co-promoters the venue joins with commas and ampersands`() {
            val autumn =
                javaClass.classLoader
                    .getResourceAsStream("scraper/schokoladen/schokoladen-overview-time-line.html")!!
                    .bufferedReader()
                    .readText()
            val event = scraper.scrape(Jsoup.parse(autumn, baseUrl), baseUrl).first { it.sourceId == "schokoladen:e20260910" }
            event.promoters shouldContainExactly listOf("beav boloney", "wild wax", "little league shows")
        }

        @Test
        fun `all events carry the required identity fields`() {
            val events = scrape()
            events.forEach { event ->
                event.title.isNotBlank() shouldBe true
                event.sourceId.startsWith("schokoladen:e") shouldBe true
                event.sourceUrl.startsWith("https://www.schokoladen-mitte.de/#") shouldBe true
            }
        }

        @Test
        fun `returns no events for a page without event blocks`() {
            val empty = scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl)
            empty.shouldBeEmpty()
        }
    }
}
