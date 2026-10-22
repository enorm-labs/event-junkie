package de.norm.events.scraper.lux

import de.norm.events.common.BERLIN
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [LuxOverviewPageScraper], against a saved copy of the real home page. */
class LuxOverviewPageScraperTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), BERLIN)
    private val scraper = LuxOverviewPageScraper(clock)
    private val baseUrl = "https://www.lux-berlin.net/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/lux/lux-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun djs(date: String) = night(date).artists.map { it.name }

    private fun parse(section: String) = scraper.scrape(Jsoup.parse("""<main class="dates">$section</main>""", baseUrl), baseUrl)

    @Test
    fun `scrape reads every public night and skips the private parties`() {
        events.map { it.eventDate } shouldContainExactly
            listOf("2026-10-15", "2026-10-16", "2026-10-22", "2026-11-05").map(LocalDate::parse)
    }

    @Test
    fun `scrape maps a fully populated night`() {
        val event = night("2026-10-15")

        event.title shouldBe "LIEBLINGSMUSIK"
        event.subtitle shouldBe "am Donnerstag"
        event.eventType shouldBe "PARTY"
        event.startTime shouldBe LocalTime.of(21, 0)
        event.doorsTime.shouldBeNull()
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "lux:2026-10-15"
        event.imageUrl shouldBe
            "https://www.lux-berlin.net/images/45a227049a8521eb06f1036b0b28ccf4a444e0d9-1204x1707--dba83e61eaa4cee4d491e71062231b95-900x.jpg"
        event.priceNote shouldBe "Eintritt Frei"
        event.free shouldBe true
        event.description shouldBe "Wir präsentieren frohgemut:\nCAROLA CAIRO\nDJ MAD MAX\n(Tresor)\nLieblingsmusik"
        event.artists.map { it.role }.distinct() shouldContainExactly listOf("DJ")
        djs("2026-10-15") shouldContainExactly listOf("CAROLA CAIRO", "DJ MAD MAX")
    }

    @Test
    fun `a donation is a price note and not free entry`() {
        val event = night("2026-10-16")

        event.priceNote shouldBe "Künstlerspende 1,- € beim ersten Getränk ... der Künstler (DJ) dankt"
        event.free shouldBe false
        event.startTime shouldBe LocalTime.of(22, 0)
        djs("2026-10-16") shouldContainExactly listOf("AL CHEM")
        event.description shouldBe "DJ tonite\nAL CHEM\n(elecdrones)\nmixes - raw - primitives"
    }

    @Test
    fun `a Tuer time is the doors time`() {
        val event = night("2026-10-22")

        event.doorsTime shouldBe LocalTime.of(21, 0)
        event.startTime.shouldBeNull()
        event.imageUrl.shouldBeNull()
        djs("2026-10-22") shouldContainExactly listOf("ZiegelMukke")
    }

    @Test
    fun `a bold block with line breaks bills every DJ and an open end is the start`() {
        val event = night("2026-11-05")

        event.startTime shouldBe LocalTime.of(21, 0)
        djs("2026-11-05") shouldContainExactly listOf("ANITA DRINK", "BLEACH", "PLASTIC PENNY", "VISTA VISIONS")
        event.description shouldBe "Vista Vision and Sheena Is presents:\nDJs\nANITA DRINK\nBLEACH\nPLASTIC PENNY\nVISTA VISIONS"
    }

    @Test
    fun `the year rolls over for a January night`() {
        val event =
            parse(
                """<section><div class="when">Fr<br>1.1.</div><div class="details"><h2>NEW YEAR</h2><p><strong>DJ ONE</strong></p></div></section>"""
            ).single()

        event.eventDate shouldBe LocalDate.of(2027, 1, 1)
    }

    @Test
    fun `a night without a readable date or title is skipped`() {
        parse(
            """
            <section><div class="when">bald</div><div class="details"><h2>SOON</h2></div></section>
            <section><div class="when">Fr<br>30.2.</div><div class="details"><h2>NOWHEN</h2></div></section>
            <section><div class="when">Fr<br>23.10.</div><div class="details"><h2> </h2></div></section>
            """.trimIndent()
        ).shouldBeEmpty()
    }

    @Test
    fun `a page without a programme yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Closed</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
