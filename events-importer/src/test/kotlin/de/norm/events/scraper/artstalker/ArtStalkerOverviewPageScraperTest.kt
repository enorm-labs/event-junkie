package de.norm.events.scraper.artstalker

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class ArtStalkerOverviewPageScraperTest {
    private val baseUrl = "https://art-stalker.reservix.de/"
    private val events =
        ArtStalkerOverviewPageScraper().scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/artstalker/artstalker-overview.html")!!
                    .bufferedReader()
                    .readText(),
                baseUrl
            )
        )

    private fun bySourceId(id: String) = events.single { it.sourceId == "art_stalker:$id" }

    @Test
    fun `reads every card on the first page`() {
        events shouldHaveSize 25
        events.map { it.sourceId }.toSet() shouldHaveSize 25
    }

    @Test
    fun `parses a concert card in full`() {
        val event = bySourceId("2535642")
        event.title shouldBe "Dan Patlansky"
        event.subtitle shouldBe "Blues Rock"
        event.eventType shouldBe EventType.CONCERT.name
        event.eventDate shouldBe LocalDate.of(2026, 10, 3)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.imageUrl shouldBe "https://cdn.reservix.com/core/img/event/detailEvent_2535642.jpg"
        event.sourceUrl shouldBe "https://art-stalker.reservix.de/tickets-dan-patlansky-blues-rock-in-berlin-art-stalker-am-3-10-2026/e2535642"
        event.ticketUrl shouldBe event.sourceUrl
        event.pricePresale shouldBe BigDecimal("21.21")
        event.artists shouldBe listOf(ScrapedArtist("Dan Patlansky", ArtistRole.HEADLINER, titleDerived = true))
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
        bySourceId("2582036").artists.map { it.name to it.role } shouldBe
            listOf("Deer Anna" to ArtistRole.HEADLINER, "Lily Rieke Marty" to ArtistRole.SUPPORT)
        bySourceId("2532014").artists.map { it.name to it.role } shouldBe
            listOf("2Mädchen und Uwe" to ArtistRole.HEADLINER, "SMILING SUN SONS" to ArtistRole.SUPPORT)
    }

    @Test
    fun `reads the acts past a format label and a presenter credit`() {
        bySourceId("2592781").artists.map { it.name } shouldBe listOf("Glam Jam", "Mission BlueZ")
        bySourceId("2485646").artists.map { it.name } shouldBe listOf("FREE COMPANY")
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

    private fun cardArtists(title: String) = card(title).artists.map { it.name to it.role }

    private fun card(title: String) =
        ArtStalkerOverviewPageScraper()
            .scrape(
                Jsoup.parse(
                    """
                    <ul data-testid="event-list"><li><a data-sync-id="1" href="https://art-stalker.reservix.de/tickets-x/e1">
                    <time datetime="2026-11-27T20:00"></time><h2>$title</h2></a></li></ul>
                    """.trimIndent(),
                    baseUrl
                )
            ).single()

    @Test
    fun `types the house formats and bills no act for them`() {
        val session = bySourceId("2560217")
        session.eventType shouldBe EventType.CONCERT.name
        session.artists.shouldBeEmpty()
        bySourceId("2556952").eventType shouldBe EventType.OTHER.name
        bySourceId("2559666").eventType shouldBe EventType.QUIZ.name
        bySourceId("2582102").eventType shouldBe EventType.READING.name
        bySourceId("2536387").eventType shouldBe EventType.PARTY.name
    }

    @Test
    fun `a free night has no price on its card`() {
        bySourceId("2556952").pricePresale.shouldBeNull()
    }

    @Test
    fun `skips a card without a date`() {
        val html =
            """
            <ul data-testid="event-list"><li><a data-sync-id="1" href="https://art-stalker.reservix.de/tickets-x/e1">
            <h2>No date</h2></a></li></ul>
            """.trimIndent()
        ArtStalkerOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl)).shouldBeEmpty()
    }
}
