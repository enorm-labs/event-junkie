package de.norm.events.scraper.frannz

import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for [FrannzOverviewPageScraper].
 *
 * Uses a saved snapshot of the real Frannz homepage as a regression fixture. The
 * clock is pinned to 2026-07-01 so the year-rollover date inference is
 * deterministic against the fixture's Juli-onward dates.
 */
class FrannzOverviewPageScraperTest {
    private val baseUrl = "https://frannz.eu/"

    // Fixed just before the fixture's earliest event (08 Juli) so July dates stay in 2026.
    private val clock =
        Clock.fixed(
            ZonedDateTime.of(2026, 7, 1, 12, 0, 0, 0, ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault()
        )
    private val scraper = FrannzOverviewPageScraper(clock = clock)
    private lateinit var html: String

    @BeforeEach
    fun setUp() {
        html =
            javaClass.classLoader
                .getResourceAsStream("scraper/frannz/frannz-overview.html")!!
                .bufferedReader()
                .readText()
    }

    private fun scrape() = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)

    @Test
    fun `scrape extracts all main-list events, excluding highlight carousel`() {
        scrape() shouldHaveSize 89
    }

    @Nested
    inner class ConcertParsing {
        @Test
        fun `parses concert with promoter, image and inferred year`() {
            val concert = scrape().first { it.title == "Freshlyground" }

            concert.eventType shouldBe "CONCERT"
            concert.eventDate shouldBe LocalDate.of(2026, 7, 9)
            concert.doorsTime shouldBe LocalTime.of(19, 0)
            concert.startTime shouldBe LocalTime.of(20, 0)
            concert.sourceUrl shouldBe "https://frannz.eu/#post-9708"
            concert.sourceId shouldBe "frannz:9708"
            concert.imageUrl shouldContain "images.copilot.events"
            concert.description shouldContain "Apartheid"
            concert.promoters shouldContainExactly listOf("Friendly Reminder", "rausgegangen")
        }

        @Test
        fun `extracts the title as headliner for concerts`() {
            val concert = scrape().first { it.title == "Freshlyground" }

            concert.artists shouldContainExactly listOf(ScrapedArtist(name = "Freshlyground", role = "HEADLINER"))
        }
    }

    @Nested
    inner class PartyParsing {
        @Test
        fun `parses party with subtitle, box-office price and ticket link`() {
            val party = scrape().first { it.title == "Tannz im Frannz -auf 2 Floors" }

            party.eventType shouldBe "PARTY"
            party.eventDate shouldBe LocalDate.of(2026, 7, 11)
            party.doorsTime.shouldBeNull()
            party.startTime shouldBe LocalTime.of(22, 0)
            party.subtitle shouldBe "1st floor: DJ Dr.M (Pop, Dance, HipHop) / 2nd floor: Rock/Indie mit DJ Stan"
            party.priceBoxOffice shouldBe BigDecimal("10.00")
            party.pricePresale.shouldBeNull()
            party.ticketUrl shouldBe "https://shop.copilot.events/frannz/events/7cb59a74-6fa0-421b-b534-9162d0bc9c16"
        }

        @Test
        fun `party events extract no artists`() {
            val party = scrape().first { it.title == "Tannz im Frannz -auf 2 Floors" }

            party.artists.shouldBeEmpty()
        }

        @Test
        fun `drops the raw ticket-markdown line from the description`() {
            val party = scrape().first { it.title == "Tannz im Frannz -auf 2 Floors" }

            party.description shouldContain "auf 2 Floors"
            party.description shouldNotContain "shop.copilot.events"
        }
    }

    @Nested
    inner class UntypedEvent {
        @Test
        fun `infers the type from the title when the article has no event_typ taxonomy class`() {
            // Frannz omits the taxonomy class for this quiz, so the venue-level
            // mapping yields null; the title-based fallback classifies it as QUIZ
            // (and extracts no artists, since a quiz names an event, not an act).
            val quiz = scrape().first { it.title == "Quiz Night Show" }

            quiz.eventType shouldBe "QUIZ"
            quiz.doorsTime shouldBe LocalTime.of(18, 0)
            quiz.startTime shouldBe LocalTime.of(19, 0)
            quiz.promoters.shouldBeEmpty()
            quiz.artists.shouldBeEmpty()
        }
    }

    @Nested
    inner class TitleCleaning {
        @Test
        fun `strips the trailing Nachholtermin reschedule note from titles`() {
            val titles = scrape().map { it.title }
            // The three rescheduled shows keep only the act name.
            titles shouldContainAll listOf("Iggi Kelly", "The Dear Hunter", "Pohlmann")
            titles.forEach { it shouldNotContain "Nachholtermin" }
        }

        @Test
        fun `drops a trailing sold-out annotation from the title and its derived headliner`() {
            // A "(ausverkauft)" suffix must not fork the act into a second artist row.
            val html =
                """
                <article id="post-1" class="events event_typ-konzert">
                    <h2 class="event-title">Singalong -Das große Mitsing-Event (ausverkauft)</h2>
                    <div class="event-day">10</div>
                    <div class="event-month">Dezember</div>
                </article>
                """.trimIndent()

            val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

            event.title shouldBe "Singalong -Das große Mitsing-Event"
            event.title shouldNotContain "ausverkauft"
            event.artists shouldContainExactly
                listOf(ScrapedArtist(name = "Singalong -Das große Mitsing-Event", role = "HEADLINER"))
        }
    }

    @Nested
    inner class RelocationStatus {
        // The venue announces a show that moved *out* of the house as a title suffix. Left unread
        // it was stored SCHEDULED at a venue it will not play, with the note baked into both the
        // title and the title-derived headliner ("Mad Tsai -verlegt ins Gretchen-" as an artist).
        @Test
        fun `reads a moved-out show as RELOCATED and keeps the note out of the title and artist`() {
            val html =
                """
                <article id="post-1" class="events event_typ-konzert">
                    <h2 class="event-title">MAD TSAI -verlegt ins Gretchen-</h2>
                    <div class="event-day">26</div>
                    <div class="event-month">September</div>
                </article>
                """.trimIndent()

            val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

            event.status shouldBe "RELOCATED"
            event.title shouldBe "MAD TSAI"
            event.title shouldNotContain "verlegt"
            event.artists shouldContainExactly listOf(ScrapedArtist(name = "MAD TSAI", role = "HEADLINER"))
            // The raw title is the note; the boundary reads the destination off it (#1551).
            event.statusNote shouldBe "MAD TSAI -verlegt ins Gretchen-"
            event.toEventEntity(venueId = 1L, venueSlug = "frannz-club", eventSourceId = 1L).relocatedTo shouldBe "Gretchen"
        }

        // The mirror case, and the reason the status is read from the title and never the subtitle:
        // every "verlegt" in the fixture belongs to a show that moved *into* Frannz and is stated
        // in the sub-title ("Die Show wird aus dem Prachtwerk ins Frannz verlegt!"). Those do take
        // place here and must stay SCHEDULED. This event also carries a "– HOCHVERLEGUNG" title
        // tail, which cleanEventTitle strips without it becoming a status.
        @Test
        fun `keeps a show that moved into the house SCHEDULED`() {
            val movedIn = scrape().first { it.title == "OCT (ON COMPANY TIME)" }

            movedIn.subtitle shouldContain "ins Frannz verlegt"
            movedIn.status shouldBe "SCHEDULED"
        }

        @Test
        fun `leaves an ordinary show SCHEDULED`() {
            scrape().first { it.title == "Freshlyground" }.status shouldBe "SCHEDULED"
        }
    }

    @Nested
    inner class DescriptionCleaning {
        // Regression for the copilot.events Markdown that Frannz renders raw: a leading `-` list
        // bullet on the "Tickets im VVK …" line hid it from the strip, and inline `[label](url)`
        // link syntax leaked into the stored description.
        @Test
        fun `strips ticket promo lines (all phrasings), list bullets and inline markdown links`() {
            val html =
                """
                <article id="post-1" class="events event_typ-konzert">
                    <h2 class="event-title">Markdown Blurb</h2>
                    <div class="event-day">10</div>
                    <div class="event-month">Dezember</div>
                    <div class="entry-content">
                        <div class="entry-content-wrap">
                            <div class="content">
                                <div class="sidebar"><span class="sidebar-val">Ort: Club</span></div>
                                -Tickets im VVK gibt es bei [<a href="https://www.eventim.de">eventim.de</a>](<a href="https://www.eventim.de">eventim.de</a>) -<br />
                                Tickets gibt es im VVK unter [<a href="https://shop.copilot.events">shop.copilot.events</a>](<a href="https://shop.copilot.events">shop.copilot.events</a>)<br />
                                Tickets im VVK gibt es hier: [<a href="https://shop.copilot.events">shop.copilot.events</a>](<a href="https://shop.copilot.events">shop.copilot.events</a>)<br />
                                - Einlass ab 19 Uhr<br />
                                Tickets für den 01.01. behalten ihre Gültigkeit.<br />
                                Mehr Infos auf [<a href="https://frannz.eu">frannz.eu</a>](<a href="https://frannz.eu">frannz.eu</a>).
                            </div>
                        </div>
                    </div>
                </article>
                """.trimIndent()

            val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

            // Both promo phrasings dropped (incl. their shop URLs); bullet stripped; link unwrapped to its label;
            // the genuine ticket-validity sentence is kept.
            event.description shouldBe
                "Einlass ab 19 Uhr\nTickets für den 01.01. behalten ihre Gültigkeit.\nMehr Infos auf frannz.eu."
            event.description shouldNotContain "im VVK gibt es"
            event.description shouldNotContain "gibt es im VVK"
            event.description shouldNotContain "eventim"
            event.description shouldNotContain "shop.copilot.events"
            event.description shouldNotContain "]("
            // Sidebar facts never leak into the blurb.
            event.description shouldNotContain "Ort: Club"
        }
    }

    @Nested
    inner class EventimTicketLine {
        private val eventimPage = "https://www.eventim.de/noapp/event/lime-garden-frannz-club-21348630/"

        private fun article(promoLine: String) =
            """
            <article id="post-1" class="events event_typ-konzert">
                <h2 class="event-title">Lime Garden</h2>
                <div class="event-day">10</div>
                <div class="event-month">Dezember</div>
                <div class="entry-content">
                    <div class="entry-content-wrap">
                        <div class="content">
                            <div class="sidebar"><span class="sidebar-val">Ort: Club</span></div>
                            $promoLine<br />
                            Die große neue Hoffnung des britischen Indie.
                        </div>
                    </div>
                </div>
            </article>
            """.trimIndent()

        // A show sold through Eventim has no copilot anchor; the promo line is where the link is (#1496).
        @Test
        fun `reads the event's Eventim page from the promo line's anchor`() {
            val event =
                scraper
                    .scrape(
                        Jsoup.parse(
                            article("""-Tickets im VVK gibt es bei [<a href="$eventimPage">www.eventim.de</a>](<a href="$eventimPage">www.eventim.de</a>) -"""),
                            baseUrl
                        ),
                        baseUrl
                    ).single()

            event.ticketUrl shouldBe eventimPage
            // Read, then still dropped from the description.
            event.description shouldBe "Die große neue Hoffnung des britischen Indie."
        }

        @Test
        fun `falls back to the seller's front page when the line only spells the host`() {
            val event =
                scraper
                    .scrape(Jsoup.parse(article("-Tickets im VVK gibt es bei [www.eventim.de](www.eventim.de) -"), baseUrl), baseUrl)
                    .single()

            event.ticketUrl shouldBe "https://www.eventim.de/"
            event.description shouldBe "Die große neue Hoffnung des britischen Indie."
        }

        @Test
        fun `leaves the link empty when the promo line names no seller`() {
            val event =
                scraper
                    .scrape(Jsoup.parse(article("Tickets im VVK gibt es bei"), baseUrl), baseUrl)
                    .single()

            event.ticketUrl.shouldBeNull()
        }
    }

    @Nested
    inner class DateYearRollover {
        @Test
        fun `assigns next year when the month-day has already passed`() {
            // Clock in July 2026; a "Januar" date rolls forward to January 2027.
            val minimalHtml =
                """
                <article id="post-1" class="events event_typ-konzert">
                    <h2 class="event-title">Rollover Test</h2>
                    <div class="event-day">10</div>
                    <div class="event-month">Januar</div>
                </article>
                """.trimIndent()

            val events = scraper.scrape(Jsoup.parse(minimalHtml, baseUrl), baseUrl)

            events shouldHaveSize 1
            events.first().eventDate shouldBe LocalDate.of(2027, 1, 10)
        }

        @Test
        fun `keeps the current year when the month-day is still ahead`() {
            val minimalHtml =
                """
                <article id="post-2" class="events event_typ-konzert">
                    <h2 class="event-title">Future Test</h2>
                    <div class="event-day">05</div>
                    <div class="event-month">Dezember</div>
                </article>
                """.trimIndent()

            val events = scraper.scrape(Jsoup.parse(minimalHtml, baseUrl), baseUrl)

            events shouldHaveSize 1
            events.first().eventDate shouldBe LocalDate.of(2026, 12, 5)
        }
    }

    @Test
    fun `skips an article without a parseable date`() {
        val minimalHtml =
            """
            <article id="post-3" class="events event_typ-konzert">
                <h2 class="event-title">No Date</h2>
            </article>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(minimalHtml, baseUrl), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `returns empty list for a page without events`() {
        val emptyHtml = "<html><body><main id='main'></main></body></html>"

        scraper.scrape(Jsoup.parse(emptyHtml, baseUrl), baseUrl).shouldBeEmpty()
    }
}
