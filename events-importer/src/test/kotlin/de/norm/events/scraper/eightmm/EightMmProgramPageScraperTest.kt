package de.norm.events.scraper.eightmm

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class EightMmProgramPageScraperTest {
    private val sourceUrl = "https://www.8mmbar.de/program"
    private val html =
        javaClass.classLoader
            .getResourceAsStream("scraper/eightmm/eightmm-program.html")!!
            .bufferedReader()
            .readText()

    private fun scrape(now: String = "2026-10-17T10:00:00Z") =
        EightMmProgramPageScraper(Clock.fixed(Instant.parse(now), ZoneId.of("Europe/Berlin")))
            .scrape(Jsoup.parse(html, sourceUrl), sourceUrl)

    private val events by lazy { scrape() }

    @Test
    fun `reads the upcoming events and leaves out the past ones`() {
        events.map { it.eventDate } shouldContainExactly
            listOf(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 17))
    }

    @Test
    fun `reads every event of the month shown`() {
        val all = scrape(now = "2026-09-01T10:00:00Z")

        all shouldHaveSize 12
        all.map { it.sourceId }.distinct() shouldHaveSize 12
    }

    @Test
    fun `maps a concert with its band and poster`() {
        val rmfc = events.first { it.eventDate == LocalDate.of(2026, 10, 17) }

        rmfc.title shouldBe "RMFC LIVE in 8MM"
        rmfc.eventType shouldBe "CONCERT"
        rmfc.startTime shouldBe LocalTime.of(19, 0)
        rmfc.endTime.shouldBeNull()
        rmfc.sourceUrl shouldBe "https://www.8mmbar.de/program/2026/10/17/rmfc-live-in-8mm"
        rmfc.sourceId shouldBe "eight_mm:2026-10-17-rmfc-live-in-8mm"
        rmfc.imageUrl!! shouldStartWith "https://images.squarespace-cdn.com/"
        rmfc.artists.map { it.name } shouldContainExactly listOf("RMFC")
    }

    @Test
    fun `dates an event by its date line, not by the slug it kept when it moved`() {
        val softPalms = scrape(now = "2026-09-01T10:00:00Z").first { it.title == "8MM Live: SOFT PALMS (US)" }

        softPalms.eventDate shouldBe LocalDate.of(2026, 10, 9)
        softPalms.startTime shouldBe LocalTime.of(11, 30)
        softPalms.sourceId shouldBe "eight_mm:2026-9-29-8mm-live-soft-palms-us"
    }

    @Test
    fun `keys each night of a residency on its date`() {
        val residency = events.filter { it.title.startsWith("JEFF CLARK") }

        residency.map { it.sourceId } shouldContainExactly
            listOf(
                "eight_mm:2026-10-25-jeff-clark-black-lips-sundays-in-october-dj-residency",
                "eight_mm:2026-10-18-jeff-clark-black-lips-sundays-in-october-dj-residency"
            )
        residency.map { it.eventType }.distinct() shouldContainExactly listOf("PARTY")
    }

    @Test
    fun `bills the DJ a residency title names, not the band in brackets`() {
        val residency = scrape(now = "2026-09-01T10:00:00Z").filter { it.sourceId.contains("jeff-clark") }

        residency shouldHaveSize 4
        residency.forEach { night ->
            night.artists.map { it.name to it.role } shouldContainExactly listOf("Jeff Clark" to "DJ")
        }
    }

    @Test
    fun `any other DJ night bills nobody`() {
        scrape(now = "2026-09-01T10:00:00Z")
            .filter { it.eventType == "PARTY" && !it.sourceId.contains("jeff-clark") }
            .flatMap { it.artists }
            .shouldBeEmpty()
    }

    @Test
    fun `reads a residency DJ only from the name before the bracket`() {
        fun billed(title: String) =
            EightMmProgramPageScraper(Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Europe/Berlin")))
                .scrape(Jsoup.parse(listing(title), sourceUrl), sourceUrl)
                .single()
                .artists
                .map { it.name to it.role }

        billed("ANNA LOU (THE CRAMPS) FRIDAYS IN MAY DJ RESIDENCY") shouldContainExactly listOf("Anna Lou" to "DJ")
        billed("ANNA LOU SUNDAY DJ RESIDENCY").shouldBeEmpty()
        billed("ANNA LOU (THE CRAMPS) DJ SET").shouldBeEmpty()
        billed("SOUL NIGHT WITH DJ ANNA LOU (THE CRAMPS)").shouldBeEmpty()
    }

    private fun listing(title: String) =
        """
        <noscript><ul><li>
          <h1><a href="/program/2026/10/4/x">$title</a></h1>
          <div>Sunday, October 4, 2026, 7:00 PM – 8:00 PM</div>
        </li></ul></noscript>
        """.trimIndent()

    @Test
    fun `strips the house frame from a concert billing`() {
        val all = scrape(now = "2026-09-01T10:00:00Z")

        all.first { it.title == "8MM Live: SOFT PALMS (US)" }.artists.map { it.name } shouldContainExactly listOf("SOFT PALMS")
        all.first { it.eventDate == LocalDate.of(2026, 10, 1) }.artists.map { it.name } shouldContainExactly
            listOf("THE TARTS", "Dane Joe", "Delton Sleaze")
        all.first { it.eventDate == LocalDate.of(2026, 10, 8) }.artists.map { it.name } shouldContainExactly listOf("LUFT")
        all.first { it.eventDate == LocalDate.of(2026, 10, 10) }.let {
            it.eventType shouldBe "PARTY"
            it.startTime shouldBe LocalTime.of(22, 0)
        }
    }

    @Test
    fun `an empty page yields no events`() {
        EightMmProgramPageScraper().scrape(Jsoup.parse("<html><body></body></html>", sourceUrl), sourceUrl).shouldBeEmpty()
    }
}
