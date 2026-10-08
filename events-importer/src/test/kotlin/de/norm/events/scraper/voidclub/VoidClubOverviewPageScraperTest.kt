package de.norm.events.scraper.voidclub

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [VoidClubOverviewPageScraper].
 *
 * Uses a real homepage snapshot pinned to a fixed clock, since the programme prints no year. The
 * rest of these tests exist because the venue overloads two of its own classes: `.void-event-lineup`
 * carries both the DJ billing and a standalone note, and `a.void-event-button` is both the ticket
 * link and the guestlist raffle.
 */
class VoidClubOverviewPageScraperTest {
    /** Pinned to the fixture's capture date so the weekday-based year inference is stable. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-04T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = VoidClubOverviewPageScraper(clock)
    private val baseUrl = "https://www.void-club.de/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        events = scraper.scrape(Jsoup.parse(fixture(), baseUrl), baseUrl)
    }

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/voidclub/voidclub-overview.html")!!
            .bufferedReader()
            .readText()

    private fun event(title: String): ScrapedEvent = events.first { it.title == title }

    @Test
    fun `extracts every event card`() {
        events shouldHaveSize 13
    }

    @Test
    fun `parses a fully populated night`() {
        val night = event("FREE PARTY")
        night.eventDate shouldBe LocalDate.of(2026, 8, 7)
        night.eventType shouldBe "PARTY"
        night.subtitle.shouldBeNull()
        night.genre shouldBe "DRUM & BASS, TECHNO"
        night.sourceUrl shouldBe baseUrl
        night.sourceId shouldBe "void_club:2026-08-07-free-party"
        night.ticketUrl shouldBe "https://ra.co/events/2494821"
        night.imageUrl shouldBe "https://www.void-club.de/img/teaser/event-13.jpg"
        night.artists.map { it.name } shouldContainExactly
            listOf("Paloma Pierini", "Dino S", "Honschu Lee", "Krakau", "Upzet", "Sagrivox", "Ed Shepherd", "buktuu")
    }

    @Test
    fun `reads the venue's free-entry wording off the ticket button`() {
        // "Free Tickets & Info" vs. "Tickets & Info" is the only free-entry signal on the page.
        events.filter { it.free }.map { it.title } shouldContainExactly listOf("FREE PARTY")
    }

    @Test
    fun `takes the ticket link, not the guestlist raffle beside it`() {
        // Two nights carry a second `a.void-event-button` linking /guestlistNN.html — the venue's own
        // competition, not a ticket shop.
        event("TORQUE").ticketUrl shouldBe "https://ra.co/events/2498567"
        event("UPZET'S BDAY").ticketUrl shouldBe "https://ra.co/events/2477634"
        events.none { it.ticketUrl?.contains("guestlist") == true } shouldBe true
    }

    @Test
    fun `reads the standalone lineup paragraph as a subtitle, not as acts`() {
        // This night states what it is part of in a second `.void-event-lineup` carrying no billing label.
        val night = event("KINDER DER NACHT")
        night.subtitle shouldBe "RAVE THE PLANET AFTER PARTY"
        night.artists.map { it.name }.none { it.contains("RAVE THE PLANET") } shouldBe true
    }

    @Test
    fun `splits a b2b slot into both DJs`() {
        event("UPZET'S BDAY").artists.map { it.name } shouldContainExactly
            listOf(
                "DE.fine",
                "Ida Scheppert",
                "Crashkitt",
                "Boudi Boudin",
                "Madame",
                "Andi Beat",
                "Bäggy",
                "Upzet",
                "Iza",
                "Dirty Plates",
                "Enjean",
                "Dub Isotope",
                "Agem",
                "Swat",
                "Unknown",
                "The Beast",
                "Shaded Lines"
            )
    }

    @Test
    fun `keeps an act whose own name contains a conjunction whole`() {
        // "Skulder & Mully" is billed as one comma segment here and as the left side of a b2b slot
        // on 17 October — splitting on "&" would invent a "Mully" that plays neither night. The
        // `(NL)` origin tags come off with the shared suffix rule (#1653).
        event("STOIC MUSIC X BREAKOUT DNB").artists.map { it.name } shouldContainExactly
            listOf("Ipkiss", "Defect", "Phasebound", "Skulder & Mully", "Anton Quasi", "Initia")
        event("STOIC MUSIC PRES. OVERVIEW BERLIN").artists.map { it.name } shouldContainExactly
            listOf("Klinical", "Rizzle", "Ewol", "Ambion", "Sub-Antics", "Skulder & Mully", "Armenez", "Initia", "Azur")
    }

    @Test
    fun `drops the venue's unannounced-lineup placeholders`() {
        // Four spellings across the programme: a whole lineup that is only a placeholder, a
        // trailing "and more", an em-dashed "more to be announced", and the em-dashed "more TBA" the
        // shared suffix rule handles (#1564).
        event("SEAZED: BOUNCE X TRANCE RAVE").artists.shouldBeEmpty()
        event("5 YEARS ANIMARUM").artists.shouldBeEmpty()
        event("KINDER DER NACHT").artists.map { it.name }.last() shouldBe "Lepido"
        events
            .first { it.title.startsWith("KINDER DER NACHT & DEXIT") }
            .artists
            .map { it.name }
            .last() shouldBe "Seimen Dexter"
        event("DIONYS: HARDTECHNO X TRANCE/BOUNCE RAVE").artists.map { it.name } shouldContainExactly listOf("Brizze", "DaSoMaZo")
        event("THERAPY SESSIONS XVII").artists.map { it.name }.last() shouldBe "Unknown"
        events.flatMap { it.artists }.none { it.name.contains("more", ignoreCase = true) } shouldBe true
        events.flatMap { it.artists }.none { it.name.contains("announced", ignoreCase = true) } shouldBe true
    }

    @Test
    fun `gives every night its room and no act a stage`() {
        event("STOIC MUSIC X BREAKOUT DNB").room shouldBe "Hall"
        event("TORQUE").room shouldBe "Club"
        event("FREE PARTY").room shouldBe "Club & Hall"
        events.flatMap { it.artists }.all { it.stage == null } shouldBe true
    }

    @Test
    fun `keeps the room of a night with no lineup yet`() {
        val unannounced = events.filter { it.artists.isEmpty() }

        unannounced.shouldNotBeEmpty()
        unannounced.all { it.room != null } shouldBe true
    }

    @Test
    fun `bills every act as a DJ and never twice on one night`() {
        events.flatMap { it.artists }.all { it.role == "DJ" } shouldBe true
        events.forEach { e -> e.artists.map { it.name.lowercase() }.distinct() shouldHaveSize e.artists.size }
    }

    @Test
    fun `infers the year from the weekday and keeps the listing chronological`() {
        events.map { it.eventDate } shouldBe events.map { it.eventDate }.sorted()
        events.first().eventDate shouldBe LocalDate.of(2026, 8, 7)
        events.last().eventDate shouldBe LocalDate.of(2026, 10, 31)
    }

    @Test
    fun `falls back to the rendered calendar spans when the accessible label is gone`() {
        val stripped = fixture().replace(Regex("""aria-label="(?:Mon|Tues|Wednes|Thurs|Fri|Satur|Sun)day, [^"]*""""), "")
        val fallback = scraper.scrape(Jsoup.parse(stripped, baseUrl), baseUrl)
        fallback.map { it.eventDate } shouldBe events.map { it.eventDate }
    }

    @Test
    fun `takes the rendered date over an aria-label copied from another card`() {
        // Live on 2026-10-08: the label was the 9 October INFECTED DNB card's, the spans the real 24 October. Both are
        // self-consistent weekdays, so only the disagreement shows it.
        val card = card(label = "Friday, October 9", day = "SAT", number = "24", month = "OCT")

        val (parsed, warnings) = withScraperWarnings { scraper.scrape(Jsoup.parse(card, baseUrl), baseUrl) }

        parsed.single().eventDate shouldBe LocalDate.of(2026, 10, 24)
        parsed.single().sourceId shouldBe "void_club:2026-10-24-infected-dnb-pres-zigi-sc-album-tour"
        val warning = warnings.single().formattedMessage
        warning shouldContain "INFECTED DNB PRES. ZIGI SC ALBUM TOUR"
        warning shouldContain "2026-10-24"
        warning shouldContain "2026-10-09"
    }

    @Test
    fun `keeps the aria-label's date when the rendered spans do not parse`() {
        val card = card(label = "Friday, October 9", day = "SAT", number = "??", month = "OCT")

        val (parsed, warnings) = withScraperWarnings { scraper.scrape(Jsoup.parse(card, baseUrl), baseUrl) }

        parsed.single().eventDate shouldBe LocalDate.of(2026, 10, 9)
        warnings.shouldBeEmpty()
    }

    @Test
    fun `logs nothing when the label and the spans agree`() {
        val (_, warnings) = withScraperWarnings { scraper.scrape(Jsoup.parse(fixture(), baseUrl), baseUrl) }

        warnings.shouldBeEmpty()
    }

    @Test
    fun `takes a teaser image only for the events the hero slider re-links`() {
        events.filter { it.imageUrl != null }.map { it.title } shouldContainExactly
            listOf("FREE PARTY", "TORQUE", "STOIC MUSIC X BREAKOUT DNB", "UPZET'S BDAY", "KINDER DER NACHT")
        event("UPZET'S BDAY").imageUrl shouldBe "https://www.void-club.de/img/teaser/event-16.jpg"
    }

    @Test
    fun `publishes no times, prices or statuses`() {
        events.all {
            it.doorsTime == null && it.startTime == null && it.pricePresale == null &&
                it.priceBoxOffice == null && it.priceNote == null && !it.soldOut && it.status == "SCHEDULED"
        } shouldBe true
    }

    @Test
    fun `returns no events for a page without a programme`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    /** One card in the live markup, dated by [label] and by the three rendered spans. */
    private fun card(
        label: String,
        day: String,
        number: String,
        month: String
    ): String =
        """
        <article class="void-event-card">
          <div class="void-event-date" aria-label="$label">
            <span class="void-event-day">$day</span>
            <span class="void-event-number">$number</span>
            <span class="void-event-month">$month</span>
          </div>
          <div class="void-event-content">
            <div class="void-event-meta"><span class="void-event-venue">VOID HALL</span></div>
            <h3 class="void-event-title">INFECTED DNB PRES. ZIGI SC ALBUM TOUR</h3>
          </div>
        </article>
        """.trimIndent()

    /** Runs [block] and returns its result beside the `WARN` lines the scraper logged. */
    private fun <T> withScraperWarnings(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(VoidClubOverviewPageScraper::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block() to appender.list.filter { it.level == Level.WARN }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
