package de.norm.events.scraper.slaughterhouse

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class SlaughterhouseOverviewPageScraperTest {
    private val baseUrl = "https://slaughterhouse-berlin.de/konzerte/"
    private val events = SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse(fixture(), baseUrl), baseUrl)

    private fun on(day: Int) = events.first { it.eventDate == LocalDate.of(2026, 10, day) }

    @Test
    fun `reads one event per entry between the separators`() {
        events.map { it.eventDate.toString() } shouldBe
            listOf("2026-10-10", "2026-10-16", "2026-10-17", "2026-10-30", "2026-10-31", "2026-11-06", "2026-12-19")
        val concert = on(16)
        concert.title shouldBe "Tyske Ludder + To Avoid"
        concert.eventType shouldBe EventType.CONCERT.name
        concert.startTime shouldBe LocalTime.of(22, 0)
        concert.sourceId shouldBe "slaughterhouse:2026-10-16"
        concert.sourceUrl shouldBe baseUrl
        concert.imageUrl shouldBe "https://slaughterhouse-berlin.de/wp-content/uploads/2026/09/image-3.png"
        concert.artists.map { it.name } shouldBe listOf("Tyske Ludder", "To Avoid")
        concert.description!! shouldNotContain "Tickets:"
    }

    @Test
    fun `unwraps a ticket link pasted through Facebook's redirect, and stores it without the click ID`() {
        on(16).ticketUrl!! shouldStartWith "https://www.tixforgigs.com/Event/67150?fbclid="
        on(16).toEventEntity(venueId = 1L, venueSlug = "slaughterhouse", eventSourceId = 1L).ticketUrl shouldBe "https://www.tixforgigs.com/Event/67150"
        on(17).ticketUrl shouldBe "https://www.eventim-light.com/de/a/5c8824131eb12100019a7054/"
    }

    @Test
    fun `reads a time line without a colon`() {
        val satisfactory = events.single { it.eventDate == LocalDate.of(2026, 12, 19) }
        satisfactory.startTime shouldBe LocalTime.of(22, 0)
        satisfactory.doorsTime.shouldBeNull()
        satisfactory.description!! shouldNotContain "Beginn"
        val madness = events.single { it.eventDate == LocalDate.of(2026, 11, 6) }
        madness.doorsTime shouldBe LocalTime.of(21, 0)
        madness.startTime.shouldBeNull()
        madness.description!! shouldNotContain "doors open"
    }

    @Test
    fun `reads the start from a doors line's caption that names the time before Beginn`() {
        val factory = on(31)
        factory.doorsTime shouldBe LocalTime.of(22, 0)
        factory.startTime shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `keeps a description line that opens with Beginn but names no time`() {
        val html =
            """
            <div class="entry-content">
            <p class="wp-block-paragraph">13.11.2026<br><strong>Konzert: Tyske Ludder</strong></p>
            <p class="wp-block-paragraph">Doors 20.30<br>Beginn der Tour in Berlin.<br>Tickets: 5 vor 12! Bis 12 Uhr 5 Euro</p>
            </div>
            """.trimIndent()
        val night = SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).single()
        night.doorsTime shouldBe LocalTime.of(20, 30)
        night.startTime.shouldBeNull()
        night.description shouldBe "Beginn der Tour in Berlin."
    }

    @Test
    fun `reads a party's style line and DJs, and its doors`() {
        val party = on(10)
        party.eventType shouldBe EventType.PARTY.name
        party.genre shouldBe "minimal, synth, wave"
        party.artists.map { it.name } shouldBe listOf("marko könig", "andre s.", "hazi")
        val factory = on(31)
        factory.artists.shouldBeEmpty()
        factory.imageUrl shouldBe "https://slaughterhouse-berlin.de/wp-content/uploads/2024/02/image-1.png"
        factory.description!! shouldNotContain "– – –"
    }

    @Test
    fun `bills the band before a title's colon and reads the door price`() {
        val night = on(30)
        night.eventType shouldBe EventType.CONCERT.name
        night.artists.map { it.name } shouldBe listOf("Capper")
        night.priceBoxOffice shouldBe BigDecimal("20.00")
        on(17).artists.map { it.name } shouldBe listOf("Blutgott")
    }

    @Test
    fun `bills the co-bill after a night's name and keeps the title`() {
        val html =
            """
            <div class="entry-content">
            <p class="wp-block-paragraph">13.11.2026<br><strong>Konzert+Party: Stoner&#8217;s Fall &#8211; Sheev + Ghostones + Caosonora</strong></p>
            <p class="wp-block-paragraph">Beginn: 21:00</p>
            </div>
            """.trimIndent()
        val night = SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).single()
        night.title shouldBe "Stoner’s Fall – Sheev + Ghostones + Caosonora"
        night.artists.map { it.name to it.role } shouldBe listOf("Sheev" to "HEADLINER", "Ghostones" to "HEADLINER", "Caosonora" to "HEADLINER")
    }

    @Test
    fun `yields nothing for a page without the post`() {
        SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/slaughterhouse/slaughterhouse-konzerte.html")!!
            .bufferedReader()
            .readText()
}
