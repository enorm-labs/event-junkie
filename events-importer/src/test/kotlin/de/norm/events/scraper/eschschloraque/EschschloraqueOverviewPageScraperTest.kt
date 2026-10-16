package de.norm.events.scraper.eschschloraque

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [EschschloraqueOverviewPageScraper].
 *
 * Parses a static snapshot of the venue's Drupal 7 home page. No clock is needed: every date
 * arrives as a full RDFa `content` datetime, so parsing is already deterministic.
 *
 * The fixture is the live page with its `<script>` elements removed — none sat inside a
 * `.node-veranstaltung`, so the parsed markup is unchanged, and leaving the venue's inlined vendor
 * JS in would fail CodeQL's `js/xss-through-dom` gate on every scan. Do not re-capture the page to
 * put them back.
 *
 * The cases the six-night live listing could not show — a title that names its own format, a plural
 * billing label, an "aka"-lookalike act name, a time-limited free-entry offer, and the four ways a
 * node fails to yield an event — come from the hand-crafted `-edge-cases.html` variant.
 */
class EschschloraqueOverviewPageScraperTest {
    private val baseUrl = "https://www.eschschloraque.de/"

    private fun scrape(fixture: String): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/eschschloraque/$fixture")!!
                .bufferedReader()
                .readText()
        return EschschloraqueOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private val events: List<ScrapedEvent> by lazy { scrape("eschschloraque-overview.html") }
    private val edgeCases: List<ScrapedEvent> by lazy { scrape("eschschloraque-overview-edge-cases.html") }

    @Test
    fun `discovers every event on the listing`() {
        events shouldHaveSize 6
        events.map { it.eventDate } shouldBe
            listOf(
                LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 6),
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 8, 19),
                LocalDate.of(2026, 8, 26)
            )
    }

    @Test
    fun `maps a fully populated event`() {
        val jubilee = events.first { it.title == "20 Jahre MissVergnügen!" }
        jubilee.eventDate shouldBe LocalDate.of(2026, 8, 12)
        jubilee.sourceUrl shouldBe "https://www.eschschloraque.de/20-jahre-missvergn%C3%BCgen-12082026"
        jubilee.sourceId shouldBe "eschschloraque:20-jahre-missvergnügen-12082026"
        jubilee.subtitle shouldBe
            "Jubiläumsparty mit der Original-DJ-Besetzung: Coost Lardy Cake & MissVergnügen Live: Nostalgican | ear def"
        jubilee.imageUrl shouldBe
            "https://www.eschschloraque.de/sites/default/files/styles/veranstaltung/public/veranstaltung%20/miss%2Bcoost.jpg?itok=sbN1o4Mj"
        jubilee.status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `reads the date from the RDFa attribute, not the German rendering`() {
        // The node renders "Donnerstag, 06. August 2026 ab 19Uhr" beside content="2026-08-06T19:00:00+02:00".
        val bingo = events.first { it.title.startsWith("BULETTEN BINGO") }
        bingo.eventDate shouldBe LocalDate.of(2026, 8, 6)
    }

    // #318 — the structured field carries one time; the prose says which of the two it is.
    @Test
    fun `a labelled pair in the prose places the structured time and fills the other slot`() {
        // `Einlass: 19:00 / Beginn: 19:30` names the structured 19:00 as the doors.
        val bingo = events.first { it.title.startsWith("BULETTEN BINGO") }
        bingo.doorsTime shouldBe LocalTime.of(19, 0)
        bingo.startTime shouldBe LocalTime.of(19, 30)
        // `DJs ab 21 Uhr, Showtime ab 22 Uhr`: the structured 21:00 is the doors, the show fills the start.
        val jubilee = events.first { it.title == "20 Jahre MissVergnügen!" }
        jubilee.doorsTime shouldBe LocalTime.of(21, 0)
        jubilee.startTime shouldBe LocalTime.of(22, 0)
        // `ab 21uhr / SHOWTIME 22uhr`: the bare `ab` beside a labelled start is the doors.
        val resitant = events.first { it.title == "MissVergnügen presents RESITANT – live" }
        resitant.doorsTime shouldBe LocalTime.of(21, 0)
        resitant.startTime shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `one time alone stays the start, never a doors time`() {
        val cats = events.first { it.title == "Hot Tunes for Cool Cats" }
        cats.doorsTime.shouldBeNull()
        cats.startTime.shouldNotBeNull()
    }

    @Test
    fun `builds sourceId from the decoded node path`() {
        // The alias is percent-encoded in the markup (…missvergn%C3%BCgen…) but stored decoded.
        events.map { it.sourceId } shouldContain "eschschloraque:cool-tunes-hot-cats-19082026"
        events.map { it.sourceId } shouldContain "eschschloraque:missvergnügen-presents-resitant-–-live-26082026"
    }

    @Test
    fun `bills a Live line as live and every other line as DJs`() {
        val jubilee = events.first { it.title == "20 Jahre MissVergnügen!" }
        jubilee.artists.map { it.name to it.role } shouldBe
            listOf(
                "Coost Lardy Cake" to "DJ",
                "MissVergnügen" to "DJ",
                "Nostalgican" to "LIVE",
                "ear def" to "LIVE"
            )
    }

    @Test
    fun `keeps the DJ prefix that is part of an act's name on an unlabelled billing line`() {
        // "DJ VELA & DJ Sky Deep" carries no colon, so nothing is a role label to strip.
        val badass = events.first { it.title == "BadassBassBombardement" }
        badass.artists.map { it.name } shouldBe listOf("DJ VELA", "DJ Sky Deep")
        badass.artists.map { it.role }.toSet() shouldBe setOf("DJ")
    }

    @Test
    fun `strips a billing label and keeps only the primary name of an aka alias`() {
        val hotTunes = events.first { it.title == "Hot Tunes for Cool Cats" }
        hotTunes.subtitle shouldBe "on the couch: Dinah Richten a.k.a.Seraphim & MissVergnügen"
        // "on the couch:" names the DJ seat, and the alias is one performer written twice.
        hotTunes.artists.map { it.name } shouldBe listOf("Dinah Richten", "MissVergnügen")
    }

    @Test
    fun `bills both DJs of a night that heads each act's own blurb with its name`() {
        // "Krawallwitz & Simon Eickenboom": one `.redsubtitle` in the intro, the other further down
        // the body, and only the first was read (#1136).
        val night = scrape("eschschloraque-overview-two-djs.html").first { it.title == "Krawallwitz & Simon Eickenboom" }
        night.artists.map { it.name to it.role } shouldBe listOf("Krawallwitz" to "DJ", "Simon Eickenboom" to "DJ")
        night.subtitle shouldBe "Krawallwitz"
        night.description.shouldNotBeNull()
        night.description shouldContain "Popping bottles"
    }

    @Test
    fun `strips a billing label in any wording when the venue sets it in its own span`() {
        // "erstmals zusammen auf der Couch:" is none of the known labels, and it was minted into
        // the first act's name (#2173). The span around it is what marks it as the label.
        val night = scrape("eschschloraque-overview-two-djs.html").first { it.title == "PingPongPremiere!" }
        night.artists.map { it.name to it.role } shouldBe listOf("Gina d`Orio" to "DJ", "MissVergnügen" to "DJ")
    }

    @Test
    fun `leaves an event billed in plain prose without a subtitle or lineup`() {
        val bingo = events.first { it.title.startsWith("BULETTEN BINGO") }
        bingo.subtitle.shouldBeNull()
        bingo.artists.shouldBeEmpty()
    }

    @Test
    fun `bills the act a presents-live title names as a headliner beside the presenting DJ`() {
        // The title names the live act the night is billed on; the subtitle bills the host as DJ (#1907).
        val resitant = events.first { it.title == "MissVergnügen presents RESITANT – live" }
        resitant.artists.map { it.name to it.role } shouldBe listOf("RESITANT" to "HEADLINER", "MissVergnügen" to "DJ")
        resitant.artists.first().titleDerived shouldBe true
        resitant.eventType shouldBe EventType.CONCERT.name

        val nights = scrape("eschschloraque-overview-two-djs.html").filter { " presents " in it.title }
        val frauke = nights.first { it.title == "MissVergnügen presents FRAUKE 400 - live!" }
        frauke.artists.map { it.name to it.role } shouldBe listOf("FRAUKE 400" to "HEADLINER", "MissVergnügen" to "DJ")
        // `live!!` reads the same way, and the title's typed backtick is folded to an apostrophe (#2174).
        nights.map { it.artists.first().name } shouldBe listOf("AUGELEKTRIK", "FRAUKE 400", "Dingo's Dream")
    }

    @Test
    fun `never mints a night's name from its title`() {
        // Only the presents-live form bills a performer; a night name stays out of the lineup.
        events
            .filterNot { " presents " in it.title }
            .flatMap { it.artists }
            .filter { it.titleDerived }
            .shouldBeEmpty()
    }

    @Test
    fun `flags free entry only where the prose says so`() {
        events.filter { it.free }.map { it.title } shouldBe
            listOf("BULETTEN BINGO – OPENAIR EDITION", "20 Jahre MissVergnügen!")
    }

    @Test
    fun `types a night from its billing and its prose, the venue publishing no categories`() {
        val types = events.associate { it.title to it.eventType }
        assertSoftly {
            // An "on the couch:" line bills the DJ seat of the venue's "Partyreihe".
            types["Hot Tunes for Cool Cats"] shouldBe EventType.PARTY.name
            // A `Live:` line bills a live act.
            types["20 Jahre MissVergnügen!"] shouldBe EventType.CONCERT.name
            // An unlabelled billing line is no cue, and nor is a bingo night's prose.
            types["BadassBassBombardement"] shouldBe EventType.OTHER.name
            types["BULETTEN BINGO – OPENAIR EDITION"] shouldBe EventType.OTHER.name
        }
    }

    @Test
    fun `types a night whose prose names its dancefloor as a club night`() {
        val node =
            """
            <div class="node-veranstaltung" about="/electric-baile-17102026">
              <h2 class="veranstaltung-title">Electric Baile</h2>
              <span class="date-display-single" content="2026-10-17T21:00:00+02:00">Samstag, 17. Oktober 2026 ab 21Uhr</span>
              <div class="field-type-text-with-summary"><p>Another night charged with Electro Synth Disco sounds, made for dancing from beginning to end.</p></div>
            </div>
            <div class="node-veranstaltung" about="/bande-a-part-27102026">
              <h2 class="veranstaltung-title">Bande á Part</h2>
              <span class="date-display-single" content="2026-10-27T20:00:00+02:00">Dienstag, 27. Oktober 2026 ab 20Uhr</span>
              <div class="field-type-text-with-summary">
                <p><span class="redsubtitle">MASTICAR EL REZO Poetic Forensics of Hate</span></p>
                <p>by Boneless Light (Dance and Performance Collective)</p>
              </div>
            </div>
            """.trimIndent()
        val nights = EschschloraqueOverviewPageScraper().scrape(Jsoup.parse(node, baseUrl), baseUrl).associate { it.title to it.eventType }

        nights["Electric Baile"] shouldBe EventType.PARTY.name
        // A performance billed on an unlabelled line, with no club-night word in its prose.
        nights["Bande á Part"] shouldBe EventType.OTHER.name
    }

    @Test
    fun `drops a billing line that repeats the night's name with a tail`() {
        val node =
            """
            <div class="node-veranstaltung" about="/methods-dance-ii-18092026">
              <h2 class="veranstaltung-title">Methods of Dance II</h2>
              <span class="date-display-single" content="2026-09-18T21:00:00+02:00">Freitag, 18. September 2026 ab 21Uhr</span>
              <div class="field-type-text-with-summary">
                <p><span class="redsubtitle">Methods Of Dance - 2 Act</span></p>
                <p><span class="redsubtitle"><span>Dj:</span> M.O.E.</span></p>
              </div>
            </div>
            <div class="node-veranstaltung" about="/krawallwitz-19092026">
              <h2 class="veranstaltung-title">Krawallwitz</h2>
              <span class="date-display-single" content="2026-09-19T21:00:00+02:00">Samstag, 19. September 2026 ab 21Uhr</span>
              <div class="field-type-text-with-summary"><p><span class="redsubtitle">Krawallwitz</span></p></div>
            </div>
            """.trimIndent()
        val lineups = EschschloraqueOverviewPageScraper().scrape(Jsoup.parse(node, baseUrl), baseUrl).associate { it.title to it.artists.map { a -> a.name } }

        lineups["Methods of Dance II"] shouldBe listOf("M.O.E.")
        // A night named after its one DJ keeps the DJ.
        lineups["Krawallwitz"] shouldBe listOf("Krawallwitz")
    }

    @Test
    fun `joins the venue's bilingual prose and excludes photo credits`() {
        val bingo = events.first { it.title.startsWith("BULETTEN BINGO") }
        val description = bingo.description!!
        description shouldContain "Freut euch auf einen Abend zwischen Bingo"
        description shouldContain "Expect an evening of bingo"
        // A per-act blurb's photo credit lives in the image field, never in the prose.
        events.first { it.title == "BadassBassBombardement" }.description!! shouldNotContain "Credit: Alexa Vachon"
    }

    @Test
    fun `carries no price or ticket data, which the venue never publishes`() {
        events.forEach {
            it.pricePresale.shouldBeNull()
            it.priceBoxOffice.shouldBeNull()
            it.priceNote.shouldBeNull()
            it.ticketUrl.shouldBeNull()
            it.genre.shouldBeNull()
        }
    }

    @Test
    fun `classifies an event whose title names its own format`() {
        edgeCases shouldHaveSize 1
        edgeCases.single().eventType shouldBe EventType.PARTY.name
    }

    @Test
    fun `strips a plural billing label and drops a bare format label from the lineup`() {
        val party = edgeCases.single()
        // "Djs:" is the label; "Akatombo" must survive the aka-alias split, "DJ-Set" is not a performer.
        party.artists.map { it.name } shouldBe listOf("Akatombo")
        party.artists.single().role shouldBe "DJ"
    }

    @Test
    fun `does not flag a time-limited free-entry offer as a free event`() {
        // "Eintritt frei bis 22 Uhr, danach 5€" is a happy hour, not a free night.
        edgeCases.single().free shouldBe false
    }

    @Test
    fun `skips a node without a parseable date, a title or a node path`() {
        // Four of the five nodes in the variant are unusable; only the party survives.
        edgeCases.map { it.title } shouldBe listOf("Sommer Party im Hof")
    }

    @Test
    fun `returns an empty list for a page with no event nodes`() {
        val emptyPage = Jsoup.parse("<html><body><div class='view-content'></div></body></html>", baseUrl)
        EschschloraqueOverviewPageScraper().scrape(emptyPage, baseUrl).shouldBeEmpty()
    }
}
