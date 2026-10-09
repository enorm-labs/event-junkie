package de.norm.events.scraper.jonnyknuppel

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.splitBackToBack
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** The fixture was saved on 2026-10-08: six nights ahead, twenty-three behind. */
class JonnyKnuppelOverviewPageScraperTest {
    private val baseUrl = "https://jonnyknueppel.de/"

    private val events by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/jonnyknuppel/jonnyknuppel-overview.html")!!
                .bufferedReader()
                .readText()
        scrape(html)
    }

    private fun scrape(html: String): List<ScrapedEvent> = JonnyKnuppelOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl)

    private fun titled(title: String) = events.single { it.title == title }

    @Test
    fun `reads every night of both lists, and skips the one titled TBA`() {
        events shouldHaveSize 28
        events.map { it.title } shouldNotContain "TBA"
        events.filter { it.eventDate >= LocalDate.of(2026, 10, 8) }.map { it.title } shouldContainExactly
            listOf(
                "Tangent Afterparty",
                "DWE - Soliparty: Diese Party schafft keine einzige neue Wohnung",
                "GaLiHü – Ein letzter Ausritt",
                "BBeatz @ Knüppel",
                "Overtime: Bambule X Kom.Bass",
                "Wir im Knüppel"
            )
    }

    @Test
    fun `reads a night with its line-up`() {
        val event = titled("Tangent Afterparty")

        event.eventType shouldBe EventType.PARTY.name
        event.eventDate shouldBe LocalDate.of(2026, 10, 9)
        event.startTime shouldBe LocalTime.of(22, 0)
        event.endDate shouldBe LocalDate.of(2026, 10, 10)
        event.endTime shouldBe LocalTime.of(7, 0)
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "jonny_knuppel:2026-10-09-2200"
        event.description.shouldBeNull()
        event.artists.map { it.name } shouldContainExactly
            listOf("illousion", "Rave Lauren", "Lilly K.", "Nein Oh Nein", "Meta Chrom", "Friedrichstraße Geist", "DSL", "Klirre", "Mittelscharf")
        event.artists.map { it.role }.toSet() shouldBe setOf("DJ")
    }

    @Test
    fun `the last night runs into November`() {
        val event = titled("Wir im Knüppel")

        event.eventDate shouldBe LocalDate.of(2026, 10, 31)
        event.endDate shouldBe LocalDate.of(2026, 11, 1)
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `two nights starting on one day get their own ids`() {
        val workshop = titled("Schallianz - Aktenzeichen Auflegen")
        val night = titled("Floathouse Studio x Elmstreet")

        workshop.eventDate shouldBe LocalDate.of(2026, 9, 4)
        workshop.endDate shouldBe LocalDate.of(2026, 9, 4)
        workshop.endTime shouldBe LocalTime.of(22, 0)
        night.eventDate shouldBe LocalDate.of(2026, 9, 4)
        workshop.sourceId shouldBe "jonny_knuppel:2026-09-04-1800"
        night.sourceId shouldBe "jonny_knuppel:2026-09-04-2200"
    }

    @Test
    fun `a back-to-back splits into its acts at the import boundary`() {
        val names = titled("astral.lab x Curiosity Pill x Speedgasm x vehemence").artists.flatMap { splitBackToBack(it) }.map { it.name }

        names shouldContain "AWHM"
        names shouldContain "dawnbreaker"
        names shouldContain "LAES LUCKY4U"
    }

    @Test
    fun `cuts a blurb written in English, then German, at its DE heading`() {
        val event = titled("Trippin Pharaohs - Midsommer Verglühen").toEventEntity(venueId = 1L, venueSlug = "jonny-knuppel", eventSourceId = 1L)

        event.description!! shouldStartWith "Trippin Pharaohs are back"
        event.descriptionAlt!! shouldStartWith "Trippin Pharaohs sind zurück"
    }

    @Test
    fun `one entry price is the door price, a price by arrival time is a note`() {
        titled("Wuza X").priceBoxOffice shouldBe BigDecimal("25")
        titled("Wuza X").endDate shouldBe LocalDate.of(2026, 8, 2)

        val tiered = titled("JK x Bordel des Arts SC")
        tiered.priceBoxOffice.shouldBeNull()
        tiered.priceNote shouldBe "22-23 Uhr: 10€; Ab 23 Uhr: 15-20€"
    }

    @Test
    fun `a night over New Year starts in the old year, and a night without an end is skipped`() {
        val html =
            """
            <ul>
              <li class="event-item" data-event-end="2027-01-01T08:00:00">
                <p class="event-title">Silvester</p><span class="event-date">3112-0101</span><span class="event-time">2200-0800</span>
              </li>
              <li class="event-item">
                <p class="event-title">Undated</p><span class="event-date">0501</span><span class="event-time">2200-0800</span>
              </li>
            </ul>
            """.trimIndent()

        val events = scrape(html)

        events.map { it.title } shouldContainExactly listOf("Silvester")
        events.single().eventDate shouldBe LocalDate.of(2026, 12, 31)
        events.single().endDate shouldBe LocalDate.of(2027, 1, 1)
    }
}
