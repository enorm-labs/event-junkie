package de.norm.events.scraper.fez

import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventFeature
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class FezSpielplanPageScraperTest {
    private val baseUrl = "https://fez-berlin.de/orte/astrid-lindgren-buehne/spielplan"
    private val clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), BERLIN)
    private val scraper = FezSpielplanPageScraper(clock)

    private val page1 by lazy { scraper.scrape(fixtureDocument("fez/fez-spielplan.html", baseUrl), baseUrl) }
    private val page2 by lazy { scraper.scrape(fixtureDocument("fez/fez-spielplan-page2.html", baseUrl), baseUrl) }

    private fun List<ScrapedEvent>.at(
        title: String,
        date: LocalDate
    ) = single { it.title == title && it.eventDate == date }

    @Test
    fun `reads every performance on the page`() {
        page1 shouldHaveSize 50
        page2 shouldHaveSize 50
        (page1 + page2).map { it.sourceId }.toSet() shouldHaveSize 100
    }

    @Test
    fun `reads a show with its room, company, ticket link and image`() {
        val show = page1.first()

        show.title shouldBe "Pappelapapp"
        show.eventDate shouldBe LocalDate.of(2026, 11, 15)
        show.startTime shouldBe LocalTime.of(14, 0)
        show.room shouldBe "Studiobühne (Raum 214)"
        show.eventType shouldBe "SHOW"
        show.subtitle shouldBe "Amai-Figurentheater Berlin"
        show.artists.shouldBeEmpty()
        show.ticketUrl shouldBe "https://tickets.fez-berlin.de/Views/Pages/Appointments/EventAppointments.aspx?EventID=2112"
        show.imageUrl?.startsWith("https://fez-berlin.de/fileadmin/") shouldBe true
        show.sourceUrl shouldBe baseUrl
        show.sourceId shouldBe "fez:2112-20261115-1400"
        show.soldOut shouldBe false
    }

    @Test
    fun `bills the performer of a concert as its act`() {
        val concert = page1.at("Das große Kinderrechte-Konzert", LocalDate.of(2026, 11, 15))

        concert.eventType shouldBe "CONCERT"
        concert.room shouldBe "Astrid-Lindgren-Bühne"
        concert.artists shouldContainExactly listOf(ScrapedArtist(name = "Honigkuchenpferde", role = ArtistRole.HEADLINER))
    }

    @Test
    fun `splits a concert's comma list into acts and keeps a band name whole`() {
        page1.at("Die Drei von der Klangstelle", LocalDate.of(2026, 11, 17)).artists.map { it.name } shouldContainExactly
            listOf("Emma Grünfeld", "Marceese", "Robert Metcalf")
        page1.first { it.title == "Vier Kerzen" }.artists.map { it.name } shouldContainExactly listOf("Robert Metcalf & Band")
    }

    @Test
    fun `types an item with no category as a show`() {
        val show = page1.first { it.title == "Schneeschön und Bitterkalt" }

        show.eventType shouldBe "SHOW"
        show.subtitle shouldBe "TheaterFusion"
    }

    @Test
    fun `marks every performance for children, with the age line as the phrase`() {
        (page1 + page2).all { event -> event.features.any { it.feature == EventFeature.FOR_CHILDREN } } shouldBe true
        page1
            .first()
            .features
            .single()
            .phrase shouldBe "empfohlen von 2-6 J."
    }

    @Test
    fun `reads a sold-out performance and keeps the word out of the room`() {
        val soldOut = page1.single { it.title == "Der Grüffelo" && it.startTime == LocalTime.of(9, 30) }

        soldOut.soldOut shouldBe true
        soldOut.room shouldBe "Astrid-Lindgren-Bühne"
    }

    @Test
    fun `dates a January performance in the next year`() {
        val january = page2.last()

        january.title shouldBe "Papiertiger"
        january.eventDate shouldBe LocalDate.of(2027, 1, 31)
        january.sourceId shouldBe "fez:3254-20270131-1400"
    }

    @Test
    fun `skips an item with no ticket link, which has no key`() {
        val html =
            """
            <div class="termin">
              <div class="date"><div class="date-weekday">Sonntag</div><div class="date-day">15.</div><div class="date-month">November</div></div>
              <div class="teaser"><h2>Ohne Link</h2><p class="time-line">14:00 Foyer</p></div>
            </div>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).shouldBeEmpty()
    }
}
