package de.norm.events.scraper.maaya

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [MaayaOverviewPageScraper].
 *
 * Parses a static snapshot of MAAYA's home page for deterministic, offline-safe testing without
 * HTTP fetching. The fixture keeps the whole page, so the **NEXT DATES** scoping is exercised
 * against the venue's other Elementor cards — the standing opening hours listed inside the section
 * and the "MAAYA Gallery" / "MAAYA Backyard" area blurbs below it.
 */
class MaayaOverviewPageScraperTest {
    private val scraper = MaayaOverviewPageScraper()
    private val sourceUrl = "https://maaya.de/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/maaya/maaya-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `reads every dated card and skips the venue's standing opening hours`() {
        // The section renders 22 cards; 15 name a date, the rest are opening hours or area blurbs.
        events shouldHaveSize 15
        events.map { it.title } shouldContainExactly
            listOf(
                "LA ISLA MAAYA X FURIOSA",
                "AFTERWORK TWERK",
                "PINK MANGO LOVE & AFROBEATS",
                "RIPPLES W/ AMINE K",
                "SUPAFLY",
                "SALSA BRAVA",
                "RHYTHM TRINITY",
                "HOMECOMING DJ WORKSHOP",
                "HOMECOMING x FDLA",
                "MAAYA 2 YEAR ANNIVERSARY",
                "THE CAVEMEN",
                "MAAYA X FADE POOL PARTY",
                "RAVE THE PLANET - TRUCK",
                "RAVE THE PLANET - AFTERPARTY",
                "RISE - OPEN AIR"
            )
    }

    @Test
    fun `maps a fully populated card`() {
        val laIsla = event("maaya:2026-08-04-la-isla-maaya-x-furiosa")
        laIsla.title shouldBe "LA ISLA MAAYA X FURIOSA"
        laIsla.eventDate shouldBe LocalDate.of(2026, 8, 4)
        laIsla.startTime shouldBe LocalTime.of(17, 0)
        laIsla.imageUrl shouldBe "https://maaya.de/wp-content/uploads/2026/08/2-768x768.png"
        laIsla.ticketUrl shouldBe "https://rausgegangen.de/en/events/furiosa-queer-latin-poolpartyvol-ii-0/"
        laIsla.sourceUrl shouldBe sourceUrl
        laIsla.free shouldBe false
        laIsla.priceNote.shouldBeNull()
    }

    @Test
    fun `identifies an event by date and title, there being no per-event page`() {
        events.map { it.sourceId }.toSet() shouldHaveSize 15
        events.map { it.sourceUrl }.toSet() shouldBe setOf(sourceUrl)
    }

    @Test
    fun `ignores the decorative meridiem the venue glues onto every 24-hour time`() {
        // "from 11:00pm – 17:00pm" is a daytime event, not a night one: the end gives the suffix away.
        val homecoming = event("maaya:2026-08-07-homecoming-x-fdla")
        homecoming.startTime shouldBe LocalTime.of(11, 0)
        homecoming.endTime shouldBe LocalTime.of(17, 0)
        homecoming.endDate shouldBe LocalDate.of(2026, 8, 7)
        // A genuinely late night reads the same way and must stay late, with no end to store.
        val afterparty = event("maaya:2026-08-15-rave-the-planet-afterparty")
        afterparty.startTime shouldBe LocalTime.of(23, 0)
        afterparty.endTime.shouldBeNull()
        // The end of the stated range is never mistaken for the start.
        event("maaya:2026-08-08-the-cavemen").startTime shouldBe LocalTime.of(19, 30)
        events.none { it.startTime == null } shouldBe true
    }

    @Test
    fun `takes the date from the digits, not the venue's weekday label`() {
        // The card reads "Thu. 05.08.2026", but 5 August 2026 is a Wednesday.
        val pinkMango = event("maaya:2026-08-05-pink-mango-love-afrobeats")
        pinkMango.eventDate shouldBe LocalDate.of(2026, 8, 5)
        pinkMango.eventDate.dayOfWeek shouldBe DayOfWeek.WEDNESDAY
    }

    @Test
    fun `reads the entry terms off the button, which is also the ticket link`() {
        // A bare "FREE ENTRY" is carried by the flag alone.
        val salsa = event("maaya:2026-08-07-salsa-brava")
        salsa.free shouldBe true
        salsa.priceNote.shouldBeNull()
        salsa.ticketUrl.shouldBeNull()

        // A qualified one is kept verbatim, since the flag cannot express the condition.
        val supafly = event("maaya:2026-08-07-supafly")
        supafly.free shouldBe true
        supafly.priceNote shouldBe "FREE ENTRY WITH 10€ VOUCHER"

        // Box-office-only entry is a note, not a free night and not a link.
        val rhythm = event("maaya:2026-08-07-rhythm-trinity")
        rhythm.free shouldBe false
        rhythm.priceNote shouldBe "TICKETS AT THE DOOR"
        rhythm.ticketUrl.shouldBeNull()

        // A bare "TICKETS" names the link and says nothing about the terms.
        val ripples = event("maaya:2026-08-06-ripples-w-amine-k")
        ripples.priceNote.shouldBeNull()
        ripples.ticketUrl shouldBe
            "https://xceed.me/en/berlin/event/ripples-with-amine-k-guests/239173/channel/maaya-berlin"
    }

    @Test
    fun `drops the zero-width space the venue pasted into a title`() {
        // The heading is "HOMECOMING DJ WORKSHOP\u200B" in the markup.
        val workshop = event("maaya:2026-08-07-homecoming-dj-workshop")
        workshop.title shouldBe "HOMECOMING DJ WORKSHOP"
    }

    @Test
    fun `types a night only when its title says so, and mints no artists`() {
        event("maaya:2026-08-09-maaya-x-fade-pool-party").eventType shouldBe EventType.PARTY.name
        event("maaya:2026-08-15-rave-the-planet-truck").eventType shouldBe EventType.PARTY.name
        // The venue states no category and its titles are series names, so a cue-less night
        // stays unknown rather than being guessed a club night or a gig.
        event("maaya:2026-08-08-the-cavemen").eventType shouldBe EventType.OTHER.name
        event("maaya:2026-08-04-la-isla-maaya-x-furiosa").eventType shouldBe EventType.OTHER.name
        events.all { it.artists.isEmpty() } shouldBe true
    }

    // A page without the block is a redesign, and the run fails rather than reporting a quiet venue (#1498).
    @Test
    fun `fails when the programme section is gone`() {
        val doc = Jsoup.parse("<html><body><section class='elementor-top-section'></section></body></html>", sourceUrl)
        shouldThrow<IllegalStateException> { scraper.scrape(doc, sourceUrl) }
    }

    @Test
    fun `returns nothing when the section renders no cards`() {
        val doc =
            Jsoup.parse(
                "<html><body><section class='elementor-top-section'><section id='events'></section></section></body></html>",
                sourceUrl
            )
        scraper.scrape(doc, sourceUrl).shouldBeEmpty()
    }

    // The venue's rebuild of September 2026 dropped the year from every date (#1517).
    @Nested
    inner class RebuiltPage {
        private val scraper = MaayaOverviewPageScraper(Clock.fixed(Instant.parse("2026-09-17T10:00:00Z"), ZoneOffset.UTC))

        private val events: List<ScrapedEvent> by lazy {
            val html =
                javaClass.classLoader
                    .getResourceAsStream("scraper/maaya/maaya-overview-2026-09.html")!!
                    .bufferedReader()
                    .readText()
            scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)
        }

        private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

        @Test
        fun `reads every card, giving a year-less date the occurrence nearest to today`() {
            // The section renders 16 columns; 10 are events, the rest a heading, spacers and the area blurbs.
            events shouldHaveSize 10
            events.map { it.eventDate } shouldContainExactly
                listOf(
                    LocalDate.of(2026, 9, 16),
                    LocalDate.of(2026, 9, 17),
                    LocalDate.of(2026, 9, 19),
                    LocalDate.of(2026, 9, 19),
                    LocalDate.of(2026, 9, 20),
                    LocalDate.of(2026, 9, 24),
                    LocalDate.of(2026, 9, 25),
                    LocalDate.of(2026, 9, 24),
                    LocalDate.of(2026, 10, 10),
                    LocalDate.of(2026, 10, 31)
                )
            events.map { it.title }.first() shouldBe "EL GRITO FIESTA MEXICANA"
        }

        @Test
        fun `honours a real meridiem and ignores a decorative one`() {
            // "06:00pm to 10:00pm" — the shop page says 18:00.
            val twoCanPlay = event("maaya:2026-09-24-two-can-play-this-game")
            twoCanPlay.startTime shouldBe LocalTime.of(18, 0)
            twoCanPlay.endTime shouldBe LocalTime.of(22, 0)
            // "2:00 p.m. to 10:00 p.m" — dotted, and a real afternoon.
            event("maaya:2026-09-19-afrolatin-open-air-festival").startTime shouldBe LocalTime.of(14, 0)
            // "16:00pm to 10:00pm" — the hour is already 24-hour, the suffix says nothing.
            val elGrito = event("maaya:2026-09-16-el-grito-fiesta-mexicana")
            elGrito.startTime shouldBe LocalTime.of(16, 0)
            elGrito.endTime shouldBe LocalTime.of(22, 0)
            // An end time carries its day, which is what the persistence boundary insists on.
            elGrito.endDate shouldBe LocalDate.of(2026, 9, 16)
            // "23:00 until late" and "14:00 to 22:00" — plain 24-hour, with and without an end.
            event("maaya:2026-09-19-summer-closing-afrohaus").startTime shouldBe LocalTime.of(23, 0)
            event("maaya:2026-09-19-summer-closing-afrohaus").endTime.shouldBeNull()
            event("maaya:2026-09-20-asian-food-festival").endTime shouldBe LocalTime.of(22, 0)
        }

        @Test
        fun `reads a two-day festival as a span, and its second start as no end`() {
            // "Sat. & Sun. 10/11.10 from 06:00pm and from 05:00pm"
            val afroeclipse = event("maaya:2026-10-10-afroeclipse")
            afroeclipse.endDate shouldBe LocalDate.of(2026, 10, 11)
            afroeclipse.startTime shouldBe LocalTime.of(18, 0)
            afroeclipse.endTime.shouldBeNull()
        }

        @Test
        fun `every card survives the persistence boundary`() {
            events.forEach { it.toEventEntity(venueId = 1L, venueSlug = "maaya", eventSourceId = 1L) }
        }

        @Test
        fun `keeps the entry note and the shop link as before`() {
            val angola = event("maaya:2026-09-17-beats-bites-angola")
            angola.free shouldBe true
            angola.priceNote.shouldBeNull()
            angola.ticketUrl.shouldBeNull()
            event("maaya:2026-10-31-maaya-halloween").ticketUrl shouldBe
                "https://xceed.me/en/berlin/event/maaya-halloween/243650/channel/maaya-berlin"
            events.none { it.imageUrl == null } shouldBe true
        }

        @Test
        fun `fails when the cards state schedules and none yields a date`() {
            val doc =
                Jsoup.parse(
                    """<section class="elementor-top-section"><section id="events">
                    <section class="elementor-inner-section"><div class="elementor-column"><div class="elementor-widget-wrap">
                    <div class="elementor-widget-heading"><h3 class="elementor-heading-title">SUPAFLY</h3></div>
                    <div class="elementor-widget-text-editor"><p>Friday, September 19th from 23:00</p></div>
                    </div></div></section></section></section>""",
                    sourceUrl
                )
            shouldThrow<IllegalStateException> { scraper.scrape(doc, sourceUrl) }
        }
    }
}
