package de.norm.events.scraper.parkaue

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventFeature
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class ParkaueMonthPageScraperTest {
    private val scraper = ParkaueMonthPageScraper()
    private val november by lazy { scrape("parkaue/parkaue-month-2026-11.html", "https://www.parkaue.de/spielplan/spielplan/2026-11/") }

    private fun scrape(
        path: String,
        url: String
    ) = scraper.scrape(fixtureDocument(path, url), url)

    private fun List<ScrapedEvent>.on(
        title: String,
        date: LocalDate
    ) = first { it.title == title && it.eventDate == date }

    private val ScrapedEvent.forChildren get() = features.firstOrNull { it.feature == EventFeature.FOR_CHILDREN }?.phrase

    @Test
    fun `reads every performance of the month`() {
        november shouldHaveSize 36
        november.map { it.sourceId }.toSet() shouldHaveSize 36
    }

    @Test
    fun `reads a play for six and up, marked for children`() {
        val play = november.first()

        play.title shouldBe "Pinocchio"
        play.eventDate shouldBe LocalDate.of(2026, 11, 15)
        play.startTime shouldBe LocalTime.of(16, 0)
        play.room shouldBe "Bühne 1"
        play.eventType shouldBe "SHOW"
        play.subtitle shouldBe "von Philipp Löhle, nach Carlo Collodi"
        play.sourceUrl shouldBe "https://www.parkaue.de/spielplan/spielplan/2026-11/pinocchio/1907/"
        play.sourceId shouldBe "parkaue:1907"
        play.forChildren shouldBe "(6+)"
        play.artists.shouldBeEmpty()
    }

    @Test
    fun `keeps a play for fourteen and up unmarked, and drops the soft hyphens from its title`() {
        val play = november.on("16GB: Tischtennisplattenpolitik", LocalDate.of(2026, 11, 16))

        play.subtitle shouldBe "über das Unbehagen weicher Hände"
        play.forChildren shouldBe null
    }

    @Test
    fun `reads a cancelled date with its end time`() {
        val cancelled = november.on("Die jüngste Tochter", LocalDate.of(2026, 11, 17))

        cancelled.status shouldBe "CANCELLED"
        cancelled.startTime shouldBe LocalTime.of(19, 0)
        cancelled.endTime shouldBe LocalTime.of(20, 30)
        cancelled.endDate shouldBe LocalDate.of(2026, 11, 17)
    }

    @Test
    fun `types a literature talk as a reading, sold out and not for children`() {
        val talk = november.on("Queer Vibes mit Hengameh Yaghoobifarah", LocalDate.of(2026, 11, 20))

        talk.eventType shouldBe "READING"
        talk.soldOut shouldBe true
        talk.room shouldBe "Studio 2"
        talk.forChildren shouldBe null
    }

    @Test
    fun `reads a bookable date's ticket link, and none for a sold-out date or one the shop does not sell`() {
        val bookable = november.on("16GB: Tischtennisplattenpolitik", LocalDate.of(2026, 11, 16))
        val soldOut = november.on("Queer Vibes mit Hengameh Yaghoobifarah", LocalDate.of(2026, 11, 20))
        val premiere = november.on("Pinocchio", LocalDate.of(2026, 11, 15))

        bookable.ticketUrl shouldBe "https://tickets.parkaue.de/eventim.webshop/webticket/shop?&event=11833&language=de"
        soldOut.ticketUrl shouldBe null
        premiere.ticketUrl shouldBe null
    }

    @Test
    fun `bills a family concert's band and marks it for children by its subtitle`() {
        val concert = november.on("Ich & Herr Meyer", LocalDate.of(2026, 11, 21))

        concert.eventType shouldBe "CONCERT"
        concert.artists shouldContainExactly listOf(ScrapedArtist(name = "Ich & Herr Meyer", role = ArtistRole.HEADLINER))
        concert.forChildren shouldBe "Familienkonzert"
    }

    @Test
    fun `drops a classroom piece, which has no start time at the theatre`() {
        november.none { it.title == "Bundesweiter Vorlesetag" } shouldBe true
    }

    @Test
    fun `drops a conference and keeps no house name as a room`() {
        val entry = scrape("parkaue/parkaue-spielplan.html", "https://www.parkaue.de/spielplan/")

        entry.none { it.title == "Verbinden statt Spalten" } shouldBe true
        entry.none { it.room == "Theater an der Parkaue" } shouldBe true
    }

    @Test
    fun `reads a production page's text and first photo`() {
        val url = "https://www.parkaue.de/spielplan/spielplan/pinocchio/1907/"
        val production = ParkaueProductionPageScraper().scrape(fixtureDocument("parkaue/parkaue-production-pinocchio.html", url))

        production.description.orEmpty() shouldStartWith "Der Tischler Geppetto und Pinocchio"
        production.imageUrl.orEmpty() shouldStartWith "https://www.parkaue.de/content-images/"
    }

    @Test
    fun `reads a production page whose text opens with an empty block and a lead line`() {
        val url = "https://www.parkaue.de/spielplan/spielplan/2026-11/wolf/1903/"
        val text = ParkaueProductionPageScraper().scrape(fixtureDocument("parkaue/parkaue-production-wolf.html", url)).description.orEmpty()

        text shouldStartWith "Wenn du etwas beobachtest, das nicht okay ist – traust du dich, etwas zu sagen?\n\nKemi findet wandern blöd"
        text shouldNotContain "Audiodeskription"
    }
}
