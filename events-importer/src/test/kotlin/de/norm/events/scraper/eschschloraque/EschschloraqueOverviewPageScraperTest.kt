package de.norm.events.scraper.eschschloraque

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
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
        jubilee.startTime shouldBe LocalTime.of(21, 0)
        jubilee.sourceUrl shouldBe "https://www.eschschloraque.de/20-jahre-missvergn%C3%BCgen-12082026"
        jubilee.sourceId shouldBe "eschschloraque:20-jahre-missvergnügen-12082026"
        jubilee.subtitle shouldBe
            "Jubiläumsparty mit der Original-DJ-Besetzung: Coost Lardy Cake & MissVergnügen Live: Nostalgican | ear def"
        jubilee.imageUrl shouldBe
            "https://www.eschschloraque.de/sites/default/files/styles/veranstaltung/public/veranstaltung%20/miss%2Bcoost.jpg?itok=sbN1o4Mj"
        jubilee.status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `reads date and start time from the RDFa attribute, not the German rendering`() {
        // The node renders "Donnerstag, 06. August 2026 ab 19Uhr" beside content="2026-08-06T19:00:00+02:00".
        val bingo = events.first { it.title.startsWith("BULETTEN BINGO") }
        bingo.eventDate shouldBe LocalDate.of(2026, 8, 6)
        bingo.startTime shouldBe LocalTime.of(19, 0)
        // The venue announces one time only; it is the start, never a separate doors time.
        bingo.doorsTime.shouldBeNull()
    }

    @Test
    fun `builds sourceId from the decoded node path`() {
        // The alias is percent-encoded in the markup (…missvergn%C3%BCgen…) but stored decoded.
        events.map { it.sourceId } shouldContain "eschschloraque:cool-tunes-hot-cats-19082026"
        events.map { it.sourceId } shouldContain "eschschloraque:missvergnügen-presents-resitant-–-live-26082026"
    }

    @Test
    fun `bills a Live line as headliners and every other line as DJs`() {
        val jubilee = events.first { it.title == "20 Jahre MissVergnügen!" }
        jubilee.artists.map { it.name to it.role } shouldBe
            listOf(
                "Coost Lardy Cake" to "DJ",
                "MissVergnügen" to "DJ",
                "Nostalgican" to "HEADLINER",
                "ear def" to "HEADLINER"
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
    fun `leaves an event billed in plain prose without a subtitle or lineup`() {
        val bingo = events.first { it.title.startsWith("BULETTEN BINGO") }
        bingo.subtitle.shouldBeNull()
        bingo.artists.shouldBeEmpty()
    }

    @Test
    fun `never mints the event title as an artist`() {
        val resitant = events.first { it.title == "MissVergnügen presents RESITANT – live" }
        // The title names the hosting series, not the performer; only the billing line is a lineup.
        resitant.artists.map { it.name } shouldBe listOf("MissVergnügen")
    }

    @Test
    fun `flags free entry only where the prose says so`() {
        events.filter { it.free }.map { it.title } shouldBe
            listOf("BULETTEN BINGO – OPENAIR EDITION", "20 Jahre MissVergnügen!")
    }

    @Test
    fun `types every event OTHER because the venue publishes no categories`() {
        events.map { it.eventType }.toSet() shouldBe setOf(EventType.OTHER.name)
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
