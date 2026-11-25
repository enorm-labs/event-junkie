package de.norm.events.scraper.kater

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.AcceptedLimitations
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.fixtureText
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
        events = scraper.scrape(fixtureDocument("kater/kater-overview.html", baseUrl), baseUrl)
    }

    private fun event(id: String): ScrapedEvent = events.first { it.sourceId == "kater:$id" }

    @Test
    fun `stores every club night Techno, House and leaves a screening untagged`() {
        val stored = AcceptedLimitations.withHouseGenre(EventSource.KATER, events)
        stored.filter { it.eventType == "PARTY" }.map { it.genre }.distinct() shouldBe listOf("Techno, House")
        stored.filter { it.eventType == "SCREENING" }.all { it.genre == null } shouldBe true
    }

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
                ScrapedArtist("Cabizbajo", ArtistRole.DJ, "HOPPER"),
                ScrapedArtist("Damon Jee", ArtistRole.DJ, "HOPPER"),
                ScrapedArtist("KALEA", ArtistRole.DJ, "HOPPER"),
                ScrapedArtist("Cosmic Cherry", ArtistRole.DJ, "ACID BOGEN"),
                ScrapedArtist("Daraio", ArtistRole.DJ, "ACID BOGEN"),
                ScrapedArtist("Andrea Fiorito", ArtistRole.DJ, "ACID BOGEN"),
                // `Saturday by Muting The Noise` sits between the acts: a floor heading, never a DJ (#1679).
                ScrapedArtist("Mystigrix", ArtistRole.DJ, "ACID BOGEN"),
                ScrapedArtist("Josiane", ArtistRole.DJ, "ACID BOGEN"),
                ScrapedArtist("TDKK", ArtistRole.DJ, "ACID BOGEN"),
                // Billed `Vovolectr0 [LIVE]` (#5487).
                ScrapedArtist("Vovolectr0", ArtistRole.LIVE, "ACID BOGEN")
            )
    }

    // Production billed `Saturday night`, `by Kater`, `by Fridas Pier` and `GYMie Kater + Rave Olympiad` as DJs (#1843).
    @Test
    fun `reads a bare weekday heading and a wrapped floor credit as no act`() {
        val html =
            """
            <article class="post-1 event type-event" id="event-1">
                <header class="entry-header"><h2 class="toggle-article">
                    <span class="date-header">09.10.</span><span class="date-title">FOREVER 25</span>
                </h2></header>
                <div class="entry-summary">
                    <p>Fr. 09.10 23:00 — So. 11.10 12:00</p>
                    <p>__________________<br/>
                        HOPPER<br/>
                        by Fridas Pier</p>
                    <p>Nümphe<br/>
                        Saturday night<br/>
                        GYMie Kater + Rave Olympiad<br/>
                        Adri Tüde</p>
                </div>
            </article>
            """.trimIndent()

        val artists = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single().artists

        artists.map { it.name } shouldContainExactly listOf("Nümphe", "Adri Tüde")
    }

    // FOREVER 25 billed its `SPECIALS` block, `Chapeau Club` and `Katerobics`, as DJs (#2967); #event-1778 on 2026-10-09.
    @Test
    fun `bills nobody under a SPECIALS heading and keeps the EXTRA floors`() {
        val html =
            """
            <article class="post-1778 event type-event" id="event-1778">
                <header class="entry-header"><h2 class="toggle-article">
                    <span class="date-header">09.10.</span><span class="date-title">FOREVER 25 curated by Enorm in Form</span>
                </h2></header>
                <div class="entry-summary">
                    <p>Fr. 09.10 22:00 — Mo. 12.10 01:00</p>
                    <p>__________________<br />
                    EXTRA by Enorm in Form</p>
                    <p>Revengedeko<br />
                    __________________<br />
                    EXTRAEXTRA by SUMPFKRÜGE</p>
                    <p>Chief Torkel<br />
                    __________________<br />
                    SPECIALS</p>
                    <p>Chapeau Club<br />
                    GYMie Kater + Rave Olympiad<br />
                    Katerobics</p>
                </div>
            </article>
            """.trimIndent()

        val artists = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single().artists

        artists shouldContainExactly
            listOf(
                ScrapedArtist("Revengedeko", ArtistRole.DJ, "EXTRA"),
                ScrapedArtist("Chief Torkel", ArtistRole.DJ, "EXTRAEXTRA")
            )
    }

    @Test
    fun `reads a specials heading in any case, and bills the floor after it again`() {
        val html =
            """
            <article class="post-2 event type-event" id="event-2">
                <header class="entry-header"><h2 class="toggle-article">
                    <span class="date-header">10.10.</span><span class="date-title">Night</span>
                </h2></header>
                <div class="entry-summary">
                    <p>Sa. 10.10 22:00 — So. 11.10 10:00</p>
                    <p>__________________<br />
                      Specials:  </p>
                    <p>Katerobics<br />
                    __________________<br />
                    HOPPER</p>
                    <p>Mona Moore</p>
                </div>
            </article>
            """.trimIndent()

        val artists = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single().artists

        artists shouldContainExactly listOf(ScrapedArtist("Mona Moore", ArtistRole.DJ, "HOPPER"))
    }

    private fun timedNight(schedule: String) =
        Jsoup.parse(
            """
            <article class="post-1961 event type-event" id="event-1961">
                <header class="entry-header"><h2 class="toggle-article">
                    <span class="date-header">21.11.</span><span class="date-title">Katernacht</span>
                </h2></header>
                <div class="entry-summary">
                    <p>$schedule</p>
                    <p>__________________<br />HOPPER</p>
                    <p>00:00 Gti<br />02:30 Per Hammar b2b Naicet<br />05:00 Hanna Baertig</p>
                    <p>__________________<br />EXTRAEXTRA</p>
                    <p>23:00 Reza Jalilian<br />01:00 Special Guest<br />03:30 – 06:00 Kotelett<br />Nick Ton Pere</p>
                </div>
            </article>
            """.trimIndent(),
            baseUrl
        )

    // Events 5011 and 6777 billed `02:30 Per Hammar` as the act's name.
    @Test
    fun `reads a leading clock as the set's start, per floor and across midnight`() {
        val artists = scraper.scrape(timedNight("Sa. 21.11 22:00 — So. 22.11 10:00"), baseUrl).single().artists

        fun at(time: String) = Instant.parse("2026-11-$time:00Z")
        artists shouldContainExactly
            listOf(
                ScrapedArtist("Gti", ArtistRole.DJ, "HOPPER", setStart = at("21T23:00")),
                ScrapedArtist("Per Hammar", ArtistRole.DJ, "HOPPER", setStart = at("22T01:30")),
                ScrapedArtist("Naicet", ArtistRole.DJ, "HOPPER", setStart = at("22T01:30")),
                ScrapedArtist("Hanna Baertig", ArtistRole.DJ, "HOPPER", setStart = at("22T04:00")),
                ScrapedArtist("Reza Jalilian", ArtistRole.DJ, "EXTRAEXTRA", setStart = at("21T22:00")),
                ScrapedArtist("Kotelett", ArtistRole.DJ, "EXTRAEXTRA", setStart = at("22T02:30")),
                ScrapedArtist("Nick Ton Pere", ArtistRole.DJ, "EXTRAEXTRA")
            )
    }

    @Test
    fun `strips the clock but dates no set on a night longer than one`() {
        val artists = scraper.scrape(timedNight("Sa. 21.11 22:00 — Di. 24.11 14:00"), baseUrl).single().artists

        artists.first() shouldBe ScrapedArtist("Gti", ArtistRole.DJ, "HOPPER")
        artists.mapNotNull { it.setStart }.shouldBeEmpty()
    }

    @Test
    fun `ends a floor at the blurb printed under its DJ`() {
        // Event 11857 (Kater post 1987), from the homepage on 2026-10-29.
        val html =
            """
            <article class="post-1987 event type-event" id="event-1987">
                <header class="entry-header"><h2 class="toggle-article">
                    <span class="date-header">13.11.</span><span class="date-title">Hot Mess with Gallinger</span>
                </h2></header>
                <div class="entry-summary">
                    <p>Fr. 13.11 17:00 — Fr. 13.11 22:00</p>
                    <p>__________________<br />
                    ACID BOGEN</p>
                    <p>Gallinger</p>
                    <p>Friday evenings there’s a Hot Mess in Kater – our evening program running parallell to the HeissaHolzmarkt Winterfestival.<br />
                    This time we invite Gallinger for some bouncy, minimal vinyl selections.<br />
                    Music from 17-22h before the night continues with Tipping Point<br />
                    Entrance via Holzmarkt: 3€ Kulturbeitrag</p>
                </div>
            </article>
            """.trimIndent()

        val artists = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single().artists

        artists shouldContainExactly listOf(ScrapedArtist("Gallinger", ArtistRole.DJ, "ACID BOGEN"))
    }

    @Test
    fun `keeps a floor whose b2b slot ends in a dotted name, and splits a featured guest`() {
        // Event 12959 (Kater post 1993), from the homepage on 2026-11-25 (#5501).
        val html =
            """
            <article class="post-1993 event type-event" id="event-1993">
                <header class="entry-header"><h2 class="toggle-article">
                    <span class="date-header">10.12.</span><span class="date-title">Shebanese à la Kater</span>
                </h2></header>
                <div class="entry-summary">
                    <p>Do. 10.12 17:00 — Fr. 11.12 06:00</p>
                    <p>10|12 – 11|12<br />
                    __________________<br />
                    ACID BOGEN</p>
                    <p>Aladdin b2b Phillip S.<br />
                    der Ackermann b2b Onix<br />
                    Dirty Hands b2b Mombaseo<br />
                    HANNAHHANSEN b2b TRVS25<br />
                    Kleingeld-Diskothek b2b La Flaca<br />
                    Nÿx b2b Shurti<br />
                    The Ralf feat. Pam, Aldarwish الدرويش &amp; Yukira [Live]</p>
                    <p>Entrance from 17:00 – 20:00 via Holzmarkt (2€ Kulturbeitrag)<br />
                    from 20:00 via Kater main entrance (10€)</p>
                </div>
            </article>
            """.trimIndent()

        val artists = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single().artists

        val djs =
            listOf(
                "Aladdin",
                "Phillip S.",
                "der Ackermann",
                "Onix",
                "Dirty Hands",
                "Mombaseo",
                "HANNAHHANSEN",
                "TRVS25",
                "Kleingeld-Diskothek",
                "La Flaca",
                "Nÿx",
                "Shurti"
            )
        val live = listOf("The Ralf", "Pam", "Aldarwish الدرويش", "Yukira")
        artists shouldContainExactly
            djs.map { ScrapedArtist(it, ArtistRole.DJ, "ACID BOGEN") } + live.map { ScrapedArtist(it, ArtistRole.LIVE, "ACID BOGEN") }
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
    fun `bills a duo by its name instead of splitting it at its members`() {
        // "Double Penetration (FLOWWW b2b Joe Cleen)" is one act, not two; the bracket names its members.
        val names = events.flatMap { it.artists }.map { it.name }
        names.contains("Double Penetration") shouldBe true
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

    @Test
    fun `reads the rare flyer the summary carries inline`() {
        // Seifentheater: KASSANDRA, from the homepage on 2026-09-30.
        val html = fixtureText("kater/kater-overview-inline-flyer.html")
        val baseUrl = "https://www.katerclub.de/"
        KaterOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).first().imageUrl shouldBe
            "https://www.katerclub.de/wp-content/uploads/2026/09/Kassandra-Facebook-Beitrag-300x300.jpeg"
    }
}
