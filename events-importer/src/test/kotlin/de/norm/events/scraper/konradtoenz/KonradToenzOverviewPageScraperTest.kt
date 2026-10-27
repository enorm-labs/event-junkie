package de.norm.events.scraper.konradtoenz

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [KonradToenzOverviewPageScraper], against a saved copy of the real home page. */
class KonradToenzOverviewPageScraperTest {
    private val scraper = KonradToenzOverviewPageScraper()
    private val baseUrl = "http://www.konradtoenzbar.de/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/konradtoenz/konradtoenz-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun section(vararg paragraphs: String) =
        Jsoup.parse(
            """<section class="container events-dates">${paragraphs.joinToString("") { "<p>$it</p>" }}</section>""",
            baseUrl
        )

    @Test
    fun `scrape reads every night with a readable date and skips the mistyped one`() {
        events shouldHaveSize 20
        events.map { it.eventDate }.none { it == LocalDate.of(2026, 10, 23) } shouldBe true
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 16)
        events.last().eventDate shouldBe LocalDate.of(2026, 11, 29)
        events.map { it.sourceId }.distinct() shouldHaveSize 20
    }

    @Test
    fun `scrape maps a night with its DJ, style line and start time`() {
        val event = night("2026-10-16")

        event.title shouldBe "Good Vibes mit DJ Turbo Pascal"
        event.subtitle shouldBe "Psych, Disco, Kraut & Beat"
        event.eventType shouldBe "PARTY"
        event.startTime shouldBe LocalTime.of(21, 0)
        event.doorsTime.shouldBeNull()
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "konrad_toenz:2026-10-16"
        event.artists.map { it.name to it.role } shouldContainExactly listOf("DJ Turbo Pascal" to ArtistRole.DJ)
        event.genre shouldBe "Disco, Krautrock"
    }

    @Test
    fun `two DJs joined by an ampersand are billed apart`() {
        night("2026-10-24").artists.map { it.name } shouldContainExactly listOf("DJ gina d`orio", "DJ johnboyadonis")
    }

    @Test
    fun `a night without a mit DJ tail bills no one`() {
        val event = night("2026-10-18")

        event.title shouldBe "Jazz-Nacht im Toenz"
        event.subtitle shouldBe "Mit Eurem Chefbarmann!!"
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `a night typed as one paragraph keeps its name and bills no one`() {
        val event = night("2026-11-20")

        event.title shouldBe "Gemütlicher Easy Listening Abend des Daumens mit DJ Herr Siebendaum Funk & Disco Spezial"
        event.subtitle.shouldBeNull()
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `an empty paragraph after the date line is ignored`() {
        val event = night("2026-11-21")

        event.title shouldBe "60 s Galore mit DJ Rob Tormento"
        event.artists.map { it.name } shouldContainExactly listOf("DJ Rob Tormento")
    }

    @Test
    fun `a weekday that contradicts the date keeps the printed date`() {
        val event = scraper.scrape(section("Montag, den 16.10.2026 ab 21:30 Uhr", "Party mit DJ One", "Soul"), baseUrl).single()

        event.eventDate shouldBe LocalDate.of(2026, 10, 16)
        event.startTime shouldBe LocalTime.of(21, 30)
    }

    @Test
    fun `an impossible date or a missing name skips the night`() {
        scraper.scrape(section("Freitag, den 31.11.2026 ab 21 Uhr", "Party"), baseUrl).shouldBeEmpty()
        scraper.scrape(section("Freitag, den 16.10.2026 ab 21 Uhr"), baseUrl).shouldBeEmpty()
        scraper.scrape(section("Good Vibes", "Soul"), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `a date line without a time leaves the start empty`() {
        scraper
            .scrape(section("Freitag, 16.10.2026", "Party"), baseUrl)
            .single()
            .startTime
            .shouldBeNull()
    }

    @Test
    fun `a page without the events block yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Freitag, den 16.10.2026</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
