package de.norm.events.scraper.liquidrom

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
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

class LiquidromOverviewPageScraperTest {
    private val baseUrl = "https://www.liquidrom-berlin.de/de/events.php"
    private val clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = LiquidromOverviewPageScraper(clock)

    private val html: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/liquidrom/liquidrom-overview.html")!!
            .bufferedReader()
            .readText()

    private val events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    private val sessions = events.filter { it.eventType == EventType.PARTY.name }
    private val specials = events.filter { it.eventType == EventType.SHOW.name }

    @Test
    fun `scrape reads every Sound Session and one event per special show`() {
        events shouldHaveSize 26
        sessions shouldHaveSize 21
        specials shouldHaveSize 5
        events.map { it.sourceId }.toSet() shouldHaveSize 26
    }

    @Test
    fun `scrape maps a Sound Session fully`() {
        val session = sessions.first()

        session.title shouldBe "Sound Session mit Vlad Yaki"
        session.eventDate shouldBe LocalDate.of(2026, 10, 27)
        session.startTime shouldBe LocalTime.of(19, 0)
        session.endDate shouldBe LocalDate.of(2026, 10, 27)
        session.endTime shouldBe LocalTime.of(23, 0)
        session.genre shouldBe "Indie Dance, Melodic Techno"
        session.sourceId shouldBe "liquidrom:sound-session-2026-10-27"
        session.sourceUrl shouldBe baseUrl
        session.artists.map { it.name to it.role } shouldContainExactly listOf("Vlad Yaki" to ArtistRole.DJ)
        session.imageUrl.shouldBeNull()
    }

    @Test
    fun `scrape takes a Sound Session's year from its month heading`() {
        sessions.single { it.eventDate == LocalDate.of(2027, 1, 16) }.startTime shouldBe LocalTime.of(14, 0)
        sessions.last().eventDate shouldBe LocalDate.of(2027, 3, 25)
    }

    @Test
    fun `scrape bills every DJ of a shared session`() {
        sessions.last().artists.map { it.name } shouldContainExactly listOf("Tropics", "Sarah", "Collin")
    }

    @Test
    fun `scrape splits a special into its show times on the nearest date`() {
        val ballet = specials.filter { it.title == "Ballet Sur_real" }

        ballet.map { it.startTime } shouldContainExactly listOf(LocalTime.of(19, 30), LocalTime.of(21, 30))
        ballet.first().eventDate shouldBe LocalDate.of(2026, 11, 18)
        ballet.first().sourceId shouldBe "liquidrom:ballet-sur-real-2026-11-18-1930"
        ballet.first().sourceUrl shouldBe "$baseUrl#ballet-sur-real"
        ballet.first().imageUrl shouldBe "https://www.liquidrom-berlin.de/images/events/ballet-sur-real.jpg"
        ballet.first().description!! shouldStartWith "Wasser trägt. Bewegung berührt."
        ballet.first().artists.shouldBeEmpty()
    }

    @Test
    fun `scrape credits the presenter a special names`() {
        val illumina = specials.filter { it.title == "ILLUMINA · A Live Light Experience" }

        illumina.map { it.startTime } shouldContainExactly listOf(LocalTime.of(19, 30), LocalTime.of(20, 30), LocalTime.of(21, 30))
        illumina.first().eventDate shouldBe LocalDate.of(2026, 12, 9)
        illumina.first().promoters shouldContainExactly listOf("Loooop")
    }

    @Test
    fun `scrape drops a past night`() {
        val later = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneId.of("Europe/Berlin"))

        val upcoming = LiquidromOverviewPageScraper(later).scrape(Jsoup.parse(html, baseUrl), baseUrl)

        upcoming.none { it.eventDate < LocalDate.of(2026, 11, 20) } shouldBe true
        upcoming.none { it.title == "Ballet Sur_real" } shouldBe true
    }

    @Test
    fun `scrape returns nothing for a page without a programme`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
