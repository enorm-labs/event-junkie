package de.norm.events.scraper.atrane

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
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

/** Unit tests for [ATraneProgrammePageScraper], against a snapshot taken on 2026-10-01. */
class ATraneProgrammePageScraperTest {
    private val sourceUrl = "https://a-trane.de/programm/"
    private val events = ATraneProgrammePageScraper().scrape(Jsoup.parse(fixture(), sourceUrl))

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/atrane/atrane-programm.html")!!
            .bufferedReader()
            .readText()

    private fun on(date: LocalDate): ScrapedEvent = events.single { it.eventDate == date }

    @Test
    fun `parses every concert of the programme once`() {
        // 61 nights: five closed days and one show in another house are left out.
        events shouldHaveSize 55
        events.map { it.sourceId }.distinct() shouldHaveSize 55
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 1)
        events.last().eventDate shouldBe LocalDate.of(2026, 12, 9)
        events.map { it.eventType }.distinct() shouldBe listOf(EventType.CONCERT.name)
    }

    @Test
    fun `parses a fully populated night`() {
        val night = on(LocalDate.of(2026, 10, 13))

        night.title shouldBe "VIGNOLO-MUELLER"
        night.subtitle shouldBe "Jazzquintett · «Jazz ohne Grenzen» · FEAT: Florian Menzel-Markus Schieferdecker-Étienne Deconfin"
        night.startTime shouldBe LocalTime.of(20, 30)
        night.genre shouldBe "MODERN TRADITION JAZZ"
        night.pricePresale shouldBe BigDecimal("25.00")
        night.free shouldBe false
        night.sourceUrl shouldBe
            "https://a-trane.de/Events-Directory/a-trane-praesentiertvignolo-muellerjazzquintettjazz-ohne-grenzenfeat-florian-menzel-markus-schieferdecker-etienne-deconfin/"
        night.sourceId shouldBe
            "a_trane:a-trane-praesentiertvignolo-muellerjazzquintettjazz-ohne-grenzenfeat-florian-menzel-markus-schieferdecker-etienne-deconfin"
        night.imageUrl shouldBe "https://a-trane.de/wp-content/uploads/2026/09/VIGNOLO-MUELLER-.jpg"
        night.description!! shouldStartWith "VIGNOLO-MUELLER\nJazzquintett"
        night.artists.map { it.name } shouldBe listOf("VIGNOLO-MUELLER")
    }

    @Test
    fun `reads an unpadded start date as wall-clock time`() {
        // "2026-11-1T20:00+2:00": the offset stays +2:00 in winter.
        val night = on(LocalDate.of(2026, 11, 1))
        night.startTime shouldBe LocalTime.of(20, 0)
        night.title shouldBe "Twin Talk «Delights»"
    }

    @Test
    fun `takes a festival night's act from its tonight-with line`() {
        val night = on(LocalDate.of(2026, 10, 29))
        night.title shouldBe "Elin Forkelid «Plays For Trane»"
        night.subtitle shouldBe "Feat: David Stackenäs-Mattias Ståhl-Vilhelm Bromander-Jon Fält"
        night.pricePresale.shouldBeNull()
    }

    @Test
    fun `marks the weekly free session free and drops its empty lineup label`() {
        val night = on(LocalDate.of(2026, 10, 5))
        night.title shouldBe "ANDREAS SCHMIDT & FRIENDS"
        night.subtitle.shouldBeNull()
        night.free shouldBe true
        night.pricePresale.shouldBeNull()
        night.genre shouldBe "modern jazz"
    }

    @Test
    fun `names the act without its quoted album title`() {
        on(LocalDate.of(2026, 10, 11)).artists.map { it.name } shouldBe listOf("Christian Frentzen")
        on(LocalDate.of(2026, 12, 5)).artists.map { it.name } shouldBe listOf("MIKE RUSSELL")
        on(LocalDate.of(2026, 10, 7)).artists.map { it.name } shouldBe listOf("ENEMY")
        on(LocalDate.of(2026, 10, 11)).title shouldBe "Christian Frentzen «PULSES»"
    }

    @Test
    fun `joins every style tag into the genre`() {
        on(LocalDate.of(2026, 11, 13)).genre shouldBe "Colorful Latin American Music Weekend, jazz tango world music"
    }

    @Test
    fun `takes the full price, not the student rate`() {
        on(LocalDate.of(2026, 10, 2)).pricePresale shouldBe BigDecimal("35.00")
        on(LocalDate.of(2026, 10, 28)).pricePresale shouldBe BigDecimal("20.00")
    }

    @Test
    fun `leaves out closed days and a show in another house`() {
        val titles = events.map { it.title.lowercase() }
        titles.filter { "geschlossen" in it }.shouldBeEmpty()
        events.map { it.eventDate } shouldNotContain LocalDate.of(2026, 11, 27)
    }

    @Test
    fun `returns nothing for a page without cards`() {
        ATraneProgrammePageScraper().scrape(Jsoup.parse("<html><body></body></html>", sourceUrl)).shouldBeEmpty()
    }

    @Test
    fun `skips a card without a readable start`() {
        val html =
            """
            <div class="eventon_list_event" data-event_id="1">
              <script type="application/ld+json">
                {"@context": "http://schema.org", "@type": "Event", "name": "A-TRANE PRÄSENTIERT:&lt;br&gt;Trio",
                 "url": "https://a-trane.de/Events-Directory/trio/", "startDate": "soon"}
              </script>
            </div>
            """.trimIndent()
        ATraneProgrammePageScraper().scrape(Jsoup.parse(html, sourceUrl)).shouldBeEmpty()
    }
}
