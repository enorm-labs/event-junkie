package de.norm.events.scraper.binuu

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.fixtureText
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [BinuuDetailPageScraper].
 *
 * Parses static snapshots of real Bi Nuu detail pages (whose data lives in the
 * embedded SvelteKit `data.item` payload) for deterministic, offline-safe
 * testing without HTTP fetching.
 */
class BinuuDetailPageScraperTest {
    private val scraper = BinuuDetailPageScraper()

    private fun parseFixture(
        fixture: String,
        sourceUrl: String
    ): ScrapedEvent = scraper.scrape(fixtureDocument("binuu/$fixture", sourceUrl), sourceUrl)!!

    private val archEnemy: ScrapedEvent by lazy {
        parseFixture("binuu-detail-arch-enemy.html", "https://binuu.de/de/events/inzpqdgvi1eab2q")
    }

    @Test
    fun `parses title, subtitle, date and both times`() {
        archEnemy.title shouldBe "Arch Enemy"
        archEnemy.subtitle shouldBe "Back To The Root Of All Evil"
        archEnemy.eventDate shouldBe LocalDate.of(2026, 7, 19)
        // The payload's `18:00:00.000Z` / `19:00:00.000Z` are UTC instants, and the venue's own page
        // renders them two hours later in summer (#1675).
        archEnemy.doorsTime shouldBe LocalTime.of(20, 0)
        archEnemy.startTime shouldBe LocalTime.of(21, 0)
    }

    @Test
    fun `derives sourceId from the event id`() {
        archEnemy.sourceId shouldBe "binuu:inzpqdgvi1eab2q"
    }

    @Test
    fun `parses the ticket URL, sold-out flag, promoters and description`() {
        archEnemy.ticketUrl shouldBe
            "https://festsaal.shop/produkte/535-tickets-arch-enemy-bi-nuu-berlin-am-19-07-2026"
        archEnemy.soldOut shouldBe true
        archEnemy.promoters shouldContainExactly listOf("Cobra Agency", "Festsaal Kreuzberg Booking")
        archEnemy.promoterWebsites shouldBe
            mapOf("Cobra Agency" to "https://cobra-agency.net", "Festsaal Kreuzberg Booking" to "https://festsaal.shop")
        archEnemy.description!! shouldContain "ARCH ENEMY return to small"
    }

    @Test
    fun `treats a lone performer as the headliner`() {
        archEnemy.artists shouldContainExactly listOf(ScrapedArtist("Arch Enemy", ArtistRole.HEADLINER))
    }

    @Test
    fun `bills nobody at a party that lists its own name as the performer`() {
        // Event 12478: the payload's performers are ["Cheers!"], the party's own title.
        val cheers = parseFixture("binuu-detail-cheers.html", "https://binuu.de/de/events/p858vjvye79vewl")
        cheers.eventType shouldBe "PARTY"
        cheers.artists shouldBe emptyList()
    }

    @Test
    fun `tags a performer named in the support line as support`() {
        val twdy =
            parseFixture("binuu-detail-this-will-destroy-you.html", "https://binuu.de/de/events/aufm93tii76xvp5")
        twdy.artists shouldContainExactly
            listOf(
                ScrapedArtist("This Will Destroy You", ArtistRole.HEADLINER),
                ScrapedArtist("MASCARA", ArtistRole.SUPPORT)
            )
        // subtitle_2 ("Support: MASCARA") feeds the roster, not the event subtitle.
        twdy.subtitle.shouldBeNull()
    }

    @Test
    fun `promotes the first performer to headliner when the support line names them all`() {
        val shadowplay =
            parseFixture("binuu-detail-shadowplay.html", "https://binuu.de/de/events/41apztb3yeneeef")
        shadowplay.artists shouldContainExactly
            listOf(
                ScrapedArtist("Solar Flake", ArtistRole.HEADLINER),
                ScrapedArtist("Black Nail Cabaret", ArtistRole.SUPPORT),
                ScrapedArtist("Unify Separate", ArtistRole.SUPPORT)
            )
        // No tickets array on this page → no ticket URL.
        shadowplay.ticketUrl.shouldBeNull()
    }

    private val bedoUrl = "https://binuu.de/de/events/nlahevqk44ms4wa"
    private val bedoHtml: String by lazy { fixtureText("binuu/binuu-detail-bedo-patron.html") }

    /** The Bedo & Patron page (2026-11-15) with [performer] billed and its blurb's `Die beiden` swapped for [pairWording]. */
    private fun bedoWith(
        performer: String = "Bedo & Patron",
        pairWording: String = "Die beiden",
        statusCode: String = "c"
    ): ScrapedEvent {
        val changed =
            bedoHtml
                .replace("performers:[\"Bedo & Patron\"]", "performers:[\"$performer\"]")
                .replace("Die beiden", pairWording)
                .replace("eventStatus:\"c\"", "eventStatus:\"$statusCode\"")
        return scraper.scrape(Jsoup.parse(changed, bedoUrl), bedoUrl)!!
    }

    @Test
    fun `splits one performer into two headliners when the blurb calls them a pair`() {
        // Event nlahevqk44ms4wa: performers ["Bedo & Patron"], blurb "Die beidenerfolgreichen Rap-Künstler …" (#5086).
        val bedo = parseFixture("binuu-detail-bedo-patron.html", bedoUrl)
        bedo.status shouldBe "CANCELLED"
        bedo.artists shouldContainExactly
            listOf(ScrapedArtist("Bedo", ArtistRole.HEADLINER), ScrapedArtist("Patron", ArtistRole.HEADLINER))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Beide ", "The two ", "the duo of "])
    fun `reads the other pair wordings as a pair`(wording: String) {
        bedoWith(pairWording = wording).artists.map { it.name } shouldContainExactly listOf("Bedo", "Patron")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Die ", "Beiderseits ", "The twofold "])
    fun `keeps an ampersand act whole when the blurb has no pair wording`(wording: String) {
        bedoWith(pairWording = wording).artists shouldContainExactly listOf(ScrapedArtist("Bedo & Patron", ArtistRole.HEADLINER))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Simon & Garfunkel", "Bedo & Friends", "Bedo & The Band", "Bedo & Patron & Ezhel", "Bedo&Patron"])
    fun `keeps a known duo, a band tail, a three-act line and an unpadded ampersand whole despite the pair wording`(performer: String) {
        bedoWith(performer = performer).artists shouldContainExactly listOf(ScrapedArtist(performer, ArtistRole.HEADLINER))
    }

    @Test
    fun `splits a pair of long names`() {
        val left = "Das Ensemble der außerordentlich langen Künstlernamen aus Berlin-Friedrichshain"
        val right = "Ein Zweiter Sehr Langer Name Für Den Zweiten Rapper Des Abends"
        bedoWith(performer = "$left & $right").artists.map { it.name } shouldContainExactly listOf(left, right)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "c", "p", "r", "rp"])
    fun `splits the pair whatever the status`(statusCode: String) {
        bedoWith(statusCode = statusCode).artists.map { it.name } shouldContainExactly listOf("Bedo", "Patron")
    }

    @Test
    fun `maps the relocated status and strips stray whitespace from the ticket URL`() {
        val oidorno = parseFixture("binuu-detail-oidorno.html", "https://binuu.de/de/events/fko44tarc3g5wlv")
        oidorno.status shouldBe "RELOCATED"
        oidorno.promoters shouldContainExactly listOf("Audiolith Booking")

        val grooveJet = parseFixture("binuu-detail-groovejet.html", "https://binuu.de/de/events/zf0kroyf2cjolyl")
        // The CMS leaves a rogue space after '?'; it must be stripped to a valid URL.
        grooveJet.ticketUrl shouldBe "https://groovejet.berlin/tickets/qbfst4q8?utm_source=binuu&utm_medium=organic"
    }

    @Test
    fun `stores a show moved to its own date as scheduled, and one without a new date as postponed`() {
        val url = "https://binuu.de/de/events/inzpqdgvi1eab2q"
        val html = fixtureText("binuu/binuu-detail-arch-enemy.html")

        fun withStatus(startOld: String): ScrapedEvent {
            val moved =
                html
                    .replace("eventStatus: null", "eventStatus: \"p\"")
                    .replace("startOld: \"\"", "startOld: \"$startOld\"")
            return scraper.scrape(Jsoup.parse(moved, url), url)!!
        }

        // "Die Veranstaltung wurde auf den 19.07.26 verschoben." names this row's date (#1689).
        withStatus("2026-03-28 19:00:00.000Z").status shouldBe "SCHEDULED"
        // No old date: the venue shows the new one as still to be announced.
        withStatus("").status shouldBe "POSTPONED"
    }

    @Test
    fun `reads a cancelled show and a move with its destination`() {
        val url = "https://binuu.de/de/events/inzpqdgvi1eab2q"
        val html = fixtureText("binuu/binuu-detail-arch-enemy.html")

        fun withStatus(
            code: String,
            location: String = ""
        ): ScrapedEvent {
            val changed =
                html
                    .replace("eventStatus: null", "eventStatus: \"$code\"")
                    .replace("startOld: \"\"", "startOld: \"\", locationArticle: \"in den\", locationNew: \"$location\"")
            return scraper.scrape(Jsoup.parse(changed, url), url)!!
        }

        // The page bundle's other two codes were stored as SCHEDULED (#1867).
        withStatus("c").status shouldBe "CANCELLED"
        val moved = withStatus("rp", "Monarch")
        moved.status shouldBe "RELOCATED"
        moved.statusNote shouldBe "Verlegt in den Monarch"
    }

    @Test
    fun `infers CONCERT for a band and PARTY for a known DJ series`() {
        // A real band with no party signal defaults to the live-music venue's norm.
        archEnemy.eventType shouldBe "CONCERT"

        // GrooveJet is a curated recurring DJ series (it lists its own name as the act),
        // so the title match flips it to PARTY.
        val grooveJet = parseFixture("binuu-detail-groovejet.html", "https://binuu.de/de/events/zf0kroyf2cjolyl")
        grooveJet.eventType shouldBe "PARTY"
    }

    @Test
    fun `returns null when the page has no item payload`() {
        val url = "https://binuu.de/de/events/x"
        scraper.scrape(Jsoup.parse("<html><body></body></html>", url), url).shouldBeNull()
    }
}
