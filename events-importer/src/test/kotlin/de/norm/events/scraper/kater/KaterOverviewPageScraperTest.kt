package de.norm.events.scraper.kater

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for [KaterOverviewPageScraper].
 *
 * Uses a real homepage snapshot pinned to a fixed clock, since the venue's dates carry a weekday
 * but no year. The behaviour worth pinning is the lineup rule: only a summary carrying the venue's
 * `____` floor rule is read as a lineup, so the garden evenings, the film night and the residency's
 * schedule prose yield no artists at all.
 */
class KaterOverviewPageScraperTest {
    /** Pinned to the fixture's capture date so the weekday-based year inference is stable. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-01T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = KaterOverviewPageScraper(clock)
    private val baseUrl = "https://www.katerclub.de/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/kater/kater-overview.html")!!
                .bufferedReader()
                .readText()
        events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(id: String): ScrapedEvent = events.first { it.sourceId == "kater:$id" }

    @Test
    fun `extracts every event article, ignoring the resident and awareness posts`() {
        // The page also renders 20 `.resident` and 2 `.awareness` articles in the same markup.
        events shouldHaveSize 26
    }

    @Test
    fun `assigns each event a unique sourceId`() {
        events.map { it.sourceId }.distinct() shouldHaveSize events.size
    }

    @Test
    fun `parses a club night with its floors and DJs`() {
        val night = event("1885")
        night.title shouldBe "Kater x Wabi-Sabi & Flirt Records"
        night.eventType shouldBe "PARTY"
        // "Sa. 01.08 22:00 — So. 02.08 10:00": both halves, each year inferred from its weekday (ADR-029).
        night.eventDate shouldBe LocalDate.of(2026, 8, 1)
        night.startTime shouldBe LocalTime.of(22, 0)
        night.endDate shouldBe LocalDate.of(2026, 8, 2)
        night.endTime shouldBe LocalTime.of(10, 0)
        night.sourceUrl shouldBe "https://www.katerclub.de/#event-1885"
        night.ticketUrl shouldBe "https://de.ra.co/events/2466351"
        night.artists shouldContainExactly
            listOf(
                ScrapedArtist("Cabizbajo", "DJ", "HOPPER"),
                ScrapedArtist("Damon Jee", "DJ", "HOPPER"),
                ScrapedArtist("KALEA", "DJ", "HOPPER"),
                ScrapedArtist("Cosmic Cherry", "DJ", "ACID BOGEN"),
                ScrapedArtist("Daraio", "DJ", "ACID BOGEN"),
                ScrapedArtist("Andrea Fiorito", "DJ", "ACID BOGEN"),
                ScrapedArtist("Mystigrix", "DJ", "ACID BOGEN"),
                ScrapedArtist("Josiane", "DJ", "ACID BOGEN"),
                ScrapedArtist("TDKK", "DJ", "ACID BOGEN"),
                ScrapedArtist("Vovolectr0", "DJ", "ACID BOGEN")
            )
    }

    @Test
    fun `strips the presenter suffix so a floor groups across nights`() {
        // The rule reads "ACID BOGEN by Wabi-Sabi & Flirt Records" on this night and plain
        // "ACID BOGEN" on others; both must land on the same stage.
        event("1885").artists.map { it.stage }.distinct() shouldContainExactly listOf("HOPPER", "ACID BOGEN")
        event("1887").artists.map { it.stage }.distinct() shouldContainExactly listOf("HOPPER", "ACID BOGEN")
    }

    @Test
    fun `derives no artists from a summary that is prose rather than a lineup`() {
        // "every tuesday * 18:00 – 01:00 *", "free entry till 20:00", "(deep house, nyc disco)" —
        // reading these as acts is exactly what the floor-rule requirement prevents.
        val funkyChicken = event("1908")
        funkyChicken.title shouldBe "The Funky Chicken Club"
        funkyChicken.artists.shouldBeEmpty()
        funkyChicken.description.shouldNotBeNull() shouldContain "every tuesday"
    }

    @Test
    fun `derives no artists from the film night's synopsis`() {
        val cinema = events.first { it.title == "Nomadenkino" }
        cinema.artists.shouldBeEmpty()
        cinema.description.shouldNotBeNull() shouldContain "EINE GESCHICHTE VON LIEBE UND FINSTERNIS"
    }

    @Test
    fun `flags a free-entry evening from its blurb`() {
        val garden = event("1886")
        garden.title shouldBe "Katergarten"
        garden.free shouldBe true
        // "Sa. 01.08 17:00 — Sa. 01.08 22:00": an end on the same day stays on it.
        garden.startTime shouldBe LocalTime.of(17, 0)
        garden.endDate shouldBe LocalDate.of(2026, 8, 1)
        garden.endTime shouldBe LocalTime.of(22, 0)
        garden.artists.shouldBeEmpty()
    }

    @Test
    fun `leaves an event without a free-entry phrase unflagged`() {
        event("1885").free shouldBe false
    }

    @Test
    fun `does not flag a time-limited free entry as a free event`() {
        // The Tuesday residency reads "free entry till 20:00" — free for two hours, not free.
        val funkyChicken = event("1908")
        funkyChicken.description.shouldNotBeNull() shouldContain "free entry till 20:00"
        funkyChicken.free shouldBe false
    }

    @Test
    fun `keeps a parenthesised duo whole instead of splitting it at its members`() {
        // "Double Penetration (FLOWWW b2b Joe Cleen)" is one act, not two.
        val names = events.flatMap { it.artists }.map { it.name }
        names.contains("Double Penetration (FLOWWW b2b Joe Cleen)") shouldBe true
        names.none { it == "Double Penetration (FLOWWW" } shouldBe true
    }

    @Test
    fun `publishes no prices or images anywhere`() {
        events.all { it.pricePresale == null && it.priceBoxOffice == null && it.imageUrl == null } shouldBe true
    }

    @Test
    fun `infers the year across the turn into the next calendar year`() {
        // The listing runs from August into the following spring with no year printed anywhere.
        events.map { it.eventDate } shouldBe events.map { it.eventDate }.sorted()
        events.first().eventDate shouldBe LocalDate.of(2026, 8, 1)
    }

    @Test
    fun `types a night as a party by default`() {
        events.count { it.eventType == "PARTY" } shouldBe events.size - events.count { it.eventType == "SCREENING" }
        event("1885").eventType shouldBe "PARTY"
    }

    @Test
    fun `returns no events for a page without event articles`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `leaves the description null when the summary is only a lineup`() {
        event("1885").description.shouldBeNull()
    }
}
