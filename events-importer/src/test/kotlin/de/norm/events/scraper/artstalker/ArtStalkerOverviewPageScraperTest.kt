package de.norm.events.scraper.artstalker

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class ArtStalkerOverviewPageScraperTest {
    private val baseUrl = "https://www.reservix.de/berlin/venue/art-stalker/v41370"
    private val events = listOf("1", "2", "6").flatMap { page(it) }

    private fun page(number: String) = ArtStalkerOverviewPageScraper().scrape(fixtureDocument("artstalker/artstalker-venue-$number.html", baseUrl))

    private fun bySourceId(id: String) = events.single { it.sourceId == "art_stalker:$id" }

    @Test
    fun `reads every card of a page`() {
        page("1") shouldHaveSize 8
        page("2") shouldHaveSize 8
        page("6") shouldHaveSize 6
        events.map { it.sourceId }.toSet() shouldHaveSize 22
    }

    @Test
    fun `parses a card in full`() {
        val event = bySourceId("2567674")
        event.title shouldBe "The Boys of Summer"
        event.subtitle shouldBe "Großstadtmusik aus Berlin"
        event.eventType shouldBe EventType.CONCERT.name
        event.eventDate shouldBe LocalDate.of(2026, 12, 12)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.imageUrl shouldBe "https://cdn.reservix.com/core/img/event/detailEvent_2567674.jpg"
        event.sourceUrl shouldBe
            "https://www.reservix.de/tickets-the-boys-of-summer-grossstadtmusik-aus-berlin-in-berlin-art-stalker-am-12-12-2026/e2567674"
        event.ticketUrl shouldBe event.sourceUrl
        event.artists shouldBe listOf(ScrapedArtist("The Boys of Summer", ArtistRole.HEADLINER, titleDerived = true))
    }

    @Test
    fun `reads the date and start however the two time lines carry them`() {
        // One line with both, and a range on the second line.
        bySourceId("2580338").run { eventDate to startTime } shouldBe (LocalDate.of(2026, 11, 26) to LocalTime.of(19, 0))
        bySourceId("2559668").run { eventDate to startTime } shouldBe (LocalDate.of(2026, 12, 9) to LocalTime.of(19, 0))
        bySourceId("2641639").eventDate shouldBe LocalDate.of(2027, 5, 6)
        bySourceId("2641911").eventDate shouldBe LocalDate.of(2027, 6, 9)
        card("X", "Do., 4. März 2027").eventDate shouldBe LocalDate.of(2027, 3, 4)
        card("X", "Sa., 4. Sept. 2027").eventDate shouldBe LocalDate.of(2027, 9, 4)
    }

    @Test
    fun `splits the tagline at the first spaced hyphen only`() {
        splitTagline("New Breed - Classic Rock - Von Hendrix bis Foo Fighters") shouldBe
            ("New Breed" to "Classic Rock - Von Hendrix bis Foo Fighters")
        splitTagline("Deer Anna – For the BirdsTour - Support: Lily Rieke Marty") shouldBe
            ("Deer Anna – For the BirdsTour" to "Support: Lily Rieke Marty")
        splitTagline("Halloween Party mit Chatterbox:CHAOS & TH74") shouldBe ("Halloween Party mit Chatterbox:CHAOS & TH74" to null)
    }

    @Test
    fun `bills the support act named in the tagline`() {
        cardArtists("Deer Anna – For the BirdsTour - Support: Lily Rieke Marty") shouldBe
            listOf("Deer Anna" to ArtistRole.HEADLINER, "Lily Rieke Marty" to ArtistRole.SUPPORT)
        cardArtists("2Mädchen und Uwe - Support Act: SMILING SUN SONS") shouldBe
            listOf("2Mädchen und Uwe" to ArtistRole.HEADLINER, "SMILING SUN SONS" to ArtistRole.SUPPORT)
    }

    @Test
    fun `reads the acts past a format label and a presenter credit`() {
        cardArtists("Doppelkonzert: Glam Jam + Mission BlueZ - Video Release").map { it.first } shouldBe listOf("Glam Jam", "Mission BlueZ")
        cardArtists("The Friendly Neighbourhood Band + Hiddit! - Dance the Night away! - Pop/Rock + Funk/Soul").map { it.first } shouldBe
            listOf("The Friendly Neighbourhood Band", "Hiddit!")
        cardArtists("BAD COMPANY- und FREE-Songs präsentiert von FREE COMPANY").map { it.first } shouldBe listOf("FREE COMPANY")
    }

    @Test
    fun `reads a trailing live und in Farbe as billing, not as a second act`() {
        cardArtists("Die Gabys live und in Farbe - Disco-, Rock- und Pop-Hits und legendäre Soulklassiker") shouldBe
            listOf("Die Gabys" to ArtistRole.HEADLINER)
        cardArtists("Die Gabys live") shouldBe listOf("Die Gabys" to ArtistRole.HEADLINER)
        cardArtists("Glam Jam und Mission BlueZ - Rock") shouldBe
            listOf("Glam Jam" to ArtistRole.HEADLINER, "Mission BlueZ" to ArtistRole.HEADLINER)
    }

    @Test
    fun `bills the band after mit der Band as a concert, past a denied karaoke cue`() {
        val event = card("Mitsing Konzert mit der Band The Rollin’ Horses - Keine Karaoke Maschine. Eine echte Live-Band")
        event.eventType shouldBe EventType.CONCERT.name
        event.subtitle shouldBe "Keine Karaoke Maschine. Eine echte Live-Band"
        event.artists.map { it.name to it.role } shouldBe listOf("The Rollin’ Horses" to ArtistRole.HEADLINER)
        card("Karaoke Abend - Mit Live-Band").eventType shouldBe EventType.PARTY.name
    }

    @Test
    fun `bills the band after Tribute mit, not the tribute`() {
        // Event 13448.
        cardArtists("Beatles Tribute mit Big Tom and the Bogsnorklers") shouldBe
            listOf("Big Tom and the Bogsnorklers" to ArtistRole.HEADLINER)
    }

    @Test
    fun `cuts a trailing backing band from the act`() {
        cardArtists("Alessandro Tarshahani and the Band - Ein Mix aus Jazz, Blues, Soul") shouldBe
            listOf("Alessandro Tarshahani" to ArtistRole.HEADLINER)
        cardArtists("Lena Meyer & Band") shouldBe listOf("Lena Meyer" to ArtistRole.HEADLINER)
        cardArtists("Lena Meyer und Band") shouldBe listOf("Lena Meyer" to ArtistRole.HEADLINER)
        cardArtists("Lena Meyer mit der Band") shouldBe listOf("Lena Meyer" to ArtistRole.HEADLINER)
        cardArtists("Doppelkonzert: Glam Jam + Lena Meyer & Band") shouldBe
            listOf("Glam Jam" to ArtistRole.HEADLINER, "Lena Meyer" to ArtistRole.HEADLINER)
        cardArtists("Beatles-Songs präsentiert von The Fab Four and the Band") shouldBe
            listOf("The Fab Four" to ArtistRole.HEADLINER)
    }

    @Test
    fun `bills the acts before präsentieren, without their friends`() {
        // Event 13980.
        cardArtists("George Kranz & Friends präsentieren das brandneue Album ‘Kuddelmuddel‘") shouldBe
            listOf("George Kranz" to ArtistRole.HEADLINER)
        cardArtists("Glam Jam und Mission BlueZ präsentieren ihr neues Album - Rock") shouldBe
            listOf("Glam Jam" to ArtistRole.HEADLINER, "Mission BlueZ" to ArtistRole.HEADLINER)
        cardArtists("BAD COMPANY- und FREE-Songs präsentiert von FREE COMPANY") shouldBe
            listOf("FREE COMPANY" to ArtistRole.HEADLINER)
    }

    @Test
    fun `bills the DJ a party's tagline names (event 15018)`() {
        val party = card("Die Early Bird Party: Der frühe Vogel tanzt den Beat - Mit DJ Louie Prima")

        party.eventType shouldBe EventType.PARTY.name
        party.artists.map { it.name to it.role } shouldBe listOf("DJ Louie Prima" to ArtistRole.DJ)
    }

    private fun cardArtists(title: String) = card(title).artists.map { it.name to it.role }

    private fun card(
        title: String,
        date: String = "Fr., 27. Nov. 2026"
    ) = ArtStalkerOverviewPageScraper()
        .scrape(Jsoup.parse(cardHtml("<h3>$title</h3><time>$date</time><time>20:00 Uhr</time>"), baseUrl))
        .single()

    private fun cardHtml(content: String) =
        """<ul class="p-venue__event-list"><li><a data-sync-id="1" href="https://www.reservix.de/tickets-x/e1">$content</a></li></ul>"""

    @Test
    fun `types the house formats and bills no act for them`() {
        val session = bySourceId("2560222")
        session.eventType shouldBe EventType.CONCERT.name
        session.artists.shouldBeEmpty()
        card("Musikschule City West: Bandstand - Meteora + Band Still Unnamed").eventType shouldBe EventType.OTHER.name
        bySourceId("2559668").eventType shouldBe EventType.QUIZ.name
        bySourceId("2582103").eventType shouldBe EventType.READING.name
        bySourceId("2536389").eventType shouldBe EventType.PARTY.name
    }

    @Test
    fun `skips a card without a date`() {
        ArtStalkerOverviewPageScraper().scrape(Jsoup.parse(cardHtml("<h3>No date</h3>"), baseUrl)).shouldBeEmpty()
    }
}
