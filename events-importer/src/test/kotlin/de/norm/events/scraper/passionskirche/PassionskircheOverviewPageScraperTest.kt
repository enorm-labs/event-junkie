package de.norm.events.scraper.passionskirche

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PassionskircheOverviewPageScraperTest {
    private val baseUrl = "https://www.halle-luja.berlin/kommende-events"
    private val clock = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val events = scrape(fixture("passionskirche-overview.html"))

    private fun scrape(html: String) = PassionskircheOverviewPageScraper(clock).scrape(Jsoup.parse(html, baseUrl), baseUrl)

    private fun bySlug(slug: String): ScrapedEvent = events.single { it.sourceId == "passionskirche:$slug" }

    @Test
    fun `reads the Passionskirche gallery only, without the Heilig-Kreuz-Kirche or the sing-along`() {
        events shouldHaveSize 11
        events.none { it.sourceId == "passionskirche:cairo-steps" } shouldBe true
        events.none { it.sourceId == "passionskirche:dark-masquerade" } shouldBe true
        events.none { it.sourceId == "passionskirche:christmas-extravaganza-yes-choir" } shouldBe true
    }

    @Test
    fun `reads a caption with a weekday, a spelled-out date and a time`() {
        val event = bySlug("everytime-we-touch-tour-2026")

        event.title shouldBe "Maggie Riley - Everytime We Touch - Tour 2026"
        event.eventDate shouldBe LocalDate.of(2026, 10, 27)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.eventType shouldBe EventType.CONCERT.name
        event.sourceUrl shouldBe "https://www.halle-luja.berlin/everytime-we-touch-tour-2026"
        event.description shouldStartWith "Nach dem großen Erfolg"
        event.imageUrl!! shouldStartWith "https://www.halle-luja.berlin/-_-/res/"
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `reads a numeric date, a date without a year and a mistyped Uhr`() {
        bySlug("the-high-kings").eventDate shouldBe LocalDate.of(2026, 11, 16)
        bySlug("rita-wilson").eventDate shouldBe LocalDate.of(2026, 11, 6)
        bySlug("rita-wilson").startTime shouldBe LocalTime.of(19, 0)
        bySlug("new-york-gospel-stars").eventDate shouldBe LocalDate.of(2027, 1, 30)
        bySlug("ida-sand").eventDate shouldBe LocalDate.of(2027, 6, 6)
        bySlug("ida-sand").startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `skips a caption without a readable date and a picture without a page`() {
        val html =
            """
            <h5>PASSIONSKIRCHE</h5>
            <div class="image-gallery-wrapper">
              <div class="glightbox-desc"><p>Teaser</p><a class="custom-link" href="/no-date">Infos</a></div>
              <p class="image-gallery-title"><span>Konzert am Sankt-Nimmerleins-Tag</span></p>
            </div>
            <div class="image-gallery-wrapper">
              <p class="image-gallery-title"><span>Band ohne Seite am 12.12.2026 um 20.00 Uhr</span></p>
            </div>
            """.trimIndent()

        scrape(html).shouldBeEmpty()
    }

    @Test
    fun `reads nothing without the church heading`() {
        val html =
            """
            <div class="image-gallery-wrapper">
              <div class="glightbox-desc"><p>Teaser</p><a class="custom-link" href="/band">Infos</a></div>
              <p class="image-gallery-title"><span>Band am 12.12.2026 um 20.00 Uhr</span></p>
            </div>
            """.trimIndent()

        scrape(html).shouldBeEmpty()
    }

    @Test
    fun `drops a church service and a sing-along`() {
        isOutOfScope("Ökumenischer Gottesdienst am 24.12.2026 um 15.00 Uhr", null) shouldBe true
        isOutOfScope("Christmas Extravaganza - Yes Choir! am 05. Dezember 2026", "mit Deutschlands spannendstem Pop-up-Chor!") shouldBe true
        isOutOfScope("The High Kings am 16.11.2026 um 20.00 Uhr", "Irlands führende Folk-Gruppe") shouldBe false
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/passionskirche/$name")!!
            .bufferedReader()
            .readText()
}
