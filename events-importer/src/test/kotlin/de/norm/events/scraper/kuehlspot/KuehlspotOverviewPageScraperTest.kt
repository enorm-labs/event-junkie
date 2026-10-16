package de.norm.events.scraper.kuehlspot

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [KuehlspotOverviewPageScraper], against a saved copy of the real home page. */
class KuehlspotOverviewPageScraperTest {
    private val scraper = KuehlspotOverviewPageScraper()
    private val baseUrl = "https://kuehlspot.com/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/kuehlspot/kuehlspot-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun acts(date: String) = night(date).artists.map { it.name }

    @Test
    fun `scrape reads every dated night and skips the series announcements`() {
        events shouldHaveSize 13
        events.map { it.eventDate.dayOfMonth } shouldContainExactly listOf(5, 7, 8, 9, 10, 12, 14, 15, 16, 19, 20, 21, 30)
        events.map { it.title }.none { it == "TRADITIONAL WEEK #1" } shouldBe true
    }

    @Test
    fun `scrape maps a night with the house times, the admission note and every musician`() {
        val event = night("2026-10-07")

        event.title shouldBe "WEBSTER / LISLE / GRIGG / BANNER"
        event.doorsTime shouldBe LocalTime.of(20, 0)
        event.startTime shouldBe LocalTime.of(20, 30)
        event.priceNote shouldBe "10 – 20 EUR donation / sliding scale"
        event.eventType shouldBe "CONCERT"
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "kuehlspot:2026-10-07"
        event.description.shouldBeNull()
        acts("2026-10-07") shouldContainExactly listOf("Colin Webster", "Matthew Grigg", "James Banner", "Andrew Lisle")
    }

    @Test
    fun `a night's heading overrides the house doors and start`() {
        val event = night("2026-10-08")

        event.doorsTime shouldBe LocalTime.of(19, 30)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.title shouldBe "TAG DER CLUBKULTUR Festival Week – Day 1"
    }

    @Test
    fun `the set headings stay in the description and the musicians of both sets are billed`() {
        val event = night("2026-10-05")

        event.description shouldBe "Set 1: THE CLIFFHANGER TRIO\nSet 2: KERBAJ / MAYAS / CORSANO"
        acts("2026-10-05") shouldContainExactly
            listOf("Ignaz Schick", "Ingebrigt Håker Flaten", "Oliver Steidle", "Mazen Kerbaj", "Magda Mayas", "Chris Corsano")
    }

    @Test
    fun `a hyphenated name and a dash without a space after it still split name from instrument`() {
        acts("2026-10-10") shouldContainExactly listOf("Simon Rose", "Viola Yip", "Nicola Hein", "Andrew Wass", "Kriton Beyer")
        acts("2026-10-30").first() shouldBe "Hui-Chun Lin"
    }

    @Test
    fun `the reading text is the description and holds no musician line`() {
        val description = night("2026-10-09").description!!

        description shouldContain "Touching Sound"
        description shouldNotContain "viola"
        description shouldNotContain "Set 2:"
    }

    @Test
    fun `a night another association runs credits it as promoter`() {
        val event = night("2026-10-19")

        event.promoters shouldContainExactly listOf("Jazzkeller 69")
        event.description.shouldBeNull()
        acts("2026-10-19") shouldContainExactly listOf("Ulrich Gumpert", "Chris Pitsiokos", "Jan Roder", "Willi Kellers")
    }

    @Test
    fun `a night with no line-up bills the title`() {
        val event = night("2026-10-15")

        event.promoters shouldContainExactly listOf("Jazzkeller 69")
        acts("2026-10-15") shouldContainExactly listOf("BERLIN JAZZ COMPOSERS ORCHESTRA JAYJAYBECE")
    }

    @Test
    fun `a band label before its line-up is neither an act nor description`() {
        val event = night("2026-10-21")

        event.description.shouldBeNull()
        acts("2026-10-21") shouldContainExactly listOf("Eutália de Carvalho", "Katrin Wahl", "Matthias Haffner", "Horst Nonnenmacher")
    }

    @Test
    fun `a page without an events section yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Closed</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
