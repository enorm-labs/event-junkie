package de.norm.events.scraper.voidclub

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
    fun `puts a single-room night's acts in that room and leaves a two-room night unattributed`() {
        event("STOIC MUSIC X BREAKOUT DNB").artists.map { it.stage }.distinct() shouldContainExactly listOf("VOID HALL")
        event("TORQUE").artists.map { it.stage }.distinct() shouldContainExactly listOf("VOID CLUB")
        // "VOID CLUB & HALL" does not say which act plays where.
        event("FREE PARTY").artists.map { it.stage }.distinct() shouldContainExactly listOf(null)
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
}
