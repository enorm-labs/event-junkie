package de.norm.events.scraper.zitadelle

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * Unit tests for [ZitadelleDetailPageScraper].
 *
 * Parses static snapshots of Citadel Music Festival `/event/<YYYY-MM-DD-slug>` pages for
 * deterministic, offline-safe testing without HTTP fetching.
 */
class ZitadelleDetailPageScraperTest {
    private val scraper = ZitadelleDetailPageScraper()

    private fun scrape(
        fixture: String,
        slug: String
    ): ScrapedEvent {
        val sourceUrl = "https://citadel-music-festival.de/event/$slug"
        return scraper.scrape(fixtureDocument("zitadelle/zitadelle-detail-$fixture.html", sourceUrl), sourceUrl)!!
    }

    private val antilopen: ScrapedEvent by lazy { scrape("antilopen-gang", "2026-08-15-antilopen-gang") }
    private val offDays: ScrapedEvent by lazy { scrape("off-days", "2026-08-19-off-days") }
    private val alexanderMarcus: ScrapedEvent by lazy { scrape("alexander-marcus", "2027-08-13-alexander-marcus") }
    private val fallOutBoy: ScrapedEvent by lazy { scrape("fall-out-boy", "2027-06-12-fall-out-boy") }
    private val jackJohnson: ScrapedEvent by lazy { scrape("jack-johnson", "2027-06-24-jack-johnson") }

    private fun billing(event: ScrapedEvent) = event.artists.map { it.name to it.role }

    private fun heroPage(
        heading: String,
        support: String,
        category: String = "konzerte"
    ): Document =
        Jsoup.parse(
            "<html><body><div class='event-hero'><h1>$heading</h1><h2 class='support'>$support</h2></div>" +
                "<article class='event type-event event-categories-$category'></article></body></html>",
            "https://citadel-music-festival.de/event/2027-06-12-x"
        )

    @Test
    fun `maps a fully populated detail page`() {
        antilopen.title shouldBe "ANTILOPEN GANG"
        antilopen.subtitle shouldBe "Das goldene Antilopen Air"
        antilopen.eventType shouldBe EventType.CONCERT.name
        antilopen.doorsTime shouldBe LocalTime.of(17, 0)
        antilopen.startTime shouldBe LocalTime.of(17, 0)
        antilopen.sourceId shouldBe "zitadelle:2026-08-15-antilopen-gang"
        antilopen.imageUrl!! shouldStartWith "https://citadel-music-festival.de/wp-content/uploads/"
        antilopen.description!! shouldContain "ANTILOPEN AIR"
        // Each paragraph and line of the description is a line of its own (#4778).
        antilopen.description
            .orEmpty()
            .lines()
            .size shouldBeGreaterThan 1
    }

    @Test
    fun `reads the times out of the labelled details rows`() {
        // "<li><span>Beginn</span>17:00</li>" — the label names the value beside it.
        antilopen.startTime shouldBe LocalTime.of(17, 0)
        offDays.doorsTime shouldBe LocalTime.of(17, 0)
        offDays.startTime shouldBe LocalTime.of(18, 0)
    }

    @Test
    fun `leaves an unannounced doors time empty rather than parsing tba`() {
        // A date a year out states "Einlass: tba".
        alexanderMarcus.doorsTime.shouldBeNull()
        alexanderMarcus.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `reads a relocated show as relocated and says where it went`() {
        // The badge reads "Abgesagt", but the change notice explains the show only moved house.
        offDays.status shouldBe EventStatus.RELOCATED.name
        offDays.soldOut shouldBe false
        offDays.description!! shouldStartWith "Wird in die Columbiahalle verlegt."
        // The event prose is kept below the notice.
        offDays.description!! shouldContain "Blood Orange"
    }

    @Test
    fun `reads the sold-out badge as a flag, not a status`() {
        antilopen.soldOut shouldBe true
        antilopen.status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `reads the external ticket-shop link`() {
        antilopen.ticketUrl!! shouldStartWith "https://www.eventim.de/"
        // Not every event uses the same shop.
        offDays.ticketUrl!! shouldStartWith "https://www.ticketmaster.de/"
    }

    @Test
    fun `stores the presenters as promoters`() {
        antilopen.promoters shouldBe listOf("Flux FM", "tip Berlin")
        offDays.promoters shouldBe listOf("Radio Eins", "Rausgegangen", "Tip")
        // An event announced before its partners are signed has none.
        alexanderMarcus.promoters.shouldBeEmpty()
    }

    @Test
    fun `types an event the taxonomy left uncategorised as a concert anyway`() {
        // Neither of these carries an `event-categories-*` class; the site programmes only music.
        antilopen.eventType shouldBe EventType.CONCERT.name
        alexanderMarcus.eventType shouldBe EventType.CONCERT.name
        alexanderMarcus.artists.map { it.name } shouldBe listOf("Alexander Marcus")
    }

    @Test
    fun `bills each act of the plus-led support line as support after the headliner`() {
        // <h1>FALL OUT BOY</h1><h2 class="support"> + A DAY TO REMEMBER + NECK DEEP</h2>
        billing(fallOutBoy) shouldBe
            listOf(
                "FALL OUT BOY" to ArtistRole.HEADLINER,
                "A DAY TO REMEMBER" to ArtistRole.SUPPORT,
                "NECK DEEP" to ArtistRole.SUPPORT
            )
        // The tour title beside it is not an act.
        fallOutBoy.subtitle shouldBe "TWILIGHT OF THE PUNKS UK EUROPE TOUR 2027"
    }

    @Test
    fun `splits a support line on and as well as on plus`() {
        // "+ Donavon Frankenreiter and G. Love"
        billing(jackJohnson) shouldBe
            listOf(
                "Jack Johnson" to ArtistRole.HEADLINER,
                "Donavon Frankenreiter" to ArtistRole.SUPPORT,
                "G. Love" to ArtistRole.SUPPORT
            )
    }

    @Test
    fun `does not read a support line without the leading plus`() {
        // "BLOOD ORANGE + MORE ACTS" under "OFF DAYS" names the night's line-up, not its support.
        offDays.artists.map { it.role } shouldBe listOf(ArtistRole.HEADLINER)
    }

    @Test
    fun `drops a placeholder from the support line`() {
        val event = scraper.scrape(heroPage("FALL OUT BOY", " + NECK DEEP + TBA"), "https://citadel-music-festival.de/event/2027-06-12-x")!!
        billing(event) shouldBe listOf("FALL OUT BOY" to ArtistRole.HEADLINER, "NECK DEEP" to ArtistRole.SUPPORT)
    }

    @Test
    fun `adds no support acts to a night that bills no headliner`() {
        val event = scraper.scrape(heroPage("SUMMER CLOSING", " + NECK DEEP", category = "festival"), "https://citadel-music-festival.de/event/2027-06-12-x")!!
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `leaves the date to the listing`() {
        // The page renders it only as long German prose ("Samstag, 15. August 2026").
        antilopen.eventDate shouldBe UNRESOLVED_EVENT_DATE
    }

    @Test
    fun `publishes no price`() {
        antilopen.pricePresale.shouldBeNull()
        antilopen.priceBoxOffice.shouldBeNull()
        antilopen.priceNote.shouldBeNull()
    }

    @Test
    fun `returns null for a page without an event heading`() {
        val sourceUrl = "https://citadel-music-festival.de/event/2026-08-15-antilopen-gang"
        val document = Jsoup.parse("<html><body><main></main></body></html>", sourceUrl)
        scraper.scrape(document, sourceUrl).shouldBeNull()
    }
}
