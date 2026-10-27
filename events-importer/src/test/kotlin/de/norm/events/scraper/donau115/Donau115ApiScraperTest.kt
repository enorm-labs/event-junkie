package de.norm.events.scraper.donau115

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The fixture is the live `events.json` of 2026-10-26, cut to the recent entries, with every booker's address replaced. */
class Donau115ApiScraperTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = Donau115ApiScraper(clock)
    private val sourceUrl = "https://www.donau115.de/"

    private val fixture: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/donau115/donau115-events.json")!!
            .bufferedReader()
            .readText()

    private val events = scraper.scrape(fixture, sourceUrl)

    private fun on(date: String): ScrapedEvent = events.single { it.eventDate == LocalDate.parse(date) }

    @Test
    fun `scrape returns one event per published upcoming night`() {
        events shouldHaveSize 42
        events.map { it.sourceId }.toSet() shouldHaveSize 42
        events.all { it.eventDate >= LocalDate.of(2026, 10, 26) } shouldBe true
    }

    @Test
    fun `scrape maps a concert night fully`() {
        val night = on("2026-10-28")

        night.title shouldBe "THE BEST THING SINCE SLICED BREAD feat. Tríne Kalniņa"
        night.sourceId shouldBe "donau115:2026-10-28"
        night.sourceUrl shouldBe sourceUrl
        night.eventType shouldBe EventType.CONCERT.name
        night.doorsTime shouldBe LocalTime.of(19, 30)
        night.startTime.shouldBeNull()
        night.imageUrl shouldBe "https://res.cloudinary.com/dci7i3mni/image/upload/v1792757833/xkdthfbybwr6tgzrcj0x.jpg"
        night.description!! shouldStartWith "After several critically acclaimed tours"
        night.artists.map { it.name to it.role } shouldContainExactly
            listOf("THE BEST THING SINCE SLICED BREAD" to ArtistRole.HEADLINER, "Tríne Kalniņa" to ArtistRole.SUPPORT)
    }

    @Test
    fun `scrape bills a shared date as one night with every act`() {
        val night = on("2026-11-25")

        night.title shouldBe "Abura Kadabura + JaMichael"
        night.artists.map { it.name } shouldContainExactly listOf("Abura Kadabura", "JaMichael")
        val description = night.description.orEmpty()
        description shouldStartWith "Abura Kadabura\n"
        description shouldContain "\n\nJaMichael\n"
    }

    @Test
    fun `scrape reads the open stage's time from its title and types it a concert with no act`() {
        val night = on("2026-10-27")

        night.title shouldBe "Two-Song Tuesday"
        night.eventType shouldBe EventType.CONCERT.name
        night.startTime shouldBe LocalTime.of(20, 0)
        night.artists.shouldBeEmpty()
    }

    @Test
    fun `scrape reads the jam's doors and opening set from the prose`() {
        val night = on("2026-10-29")

        night.eventType shouldBe EventType.CONCERT.name
        night.doorsTime shouldBe LocalTime.of(19, 30)
        night.startTime shouldBe LocalTime.of(20, 0)
        night.artists.shouldBeEmpty()
    }

    @Test
    fun `scrape skips an unpublished entry and keeps its night's published act`() {
        on("2026-10-30").title shouldBe "Yamani | Spallati | Alunni"
        events.none { it.eventDate == LocalDate.of(2026, 11, 22) } shouldBe true
    }

    @Test
    fun `scrape bills a group named by its members as each member`() {
        on("2026-10-30").artists.map { it.name } shouldContainExactly listOf("Yamani", "Spallati", "Alunni")
        on("2026-11-11").artists.map { it.name } shouldContainExactly
            listOf("Daniel Erdmann", "Fabian Willmann", "Andreas Lang", "Eva Klesse")
    }

    @Test
    fun `scrape cuts a quoted work, a series number and SOLO from an act's name`() {
        on("2026-11-18").artists.first().name shouldBe "Matthieu Mazué"
        on("2026-12-04").artists.map { it.name } shouldContainExactly listOf("Igor Osypov")
        on("2026-12-05").artists.map { it.name } shouldContainExactly listOf("Tenor Madness", "Otis Sandsjö")
        on("2026-12-09").artists.map { it.name } shouldContainExactly listOf("Leif Berger", "Kaster")
    }

    @Test
    fun `scrape keeps a night without an image`() {
        on("2026-11-28").imageUrl.shouldBeNull()
    }

    @Test
    fun `scrape never reads the booker's address`() {
        events.forEach { it.description.orEmpty() shouldNotContain "booking@example.org" }
    }

    @Test
    fun `scrape returns nothing for an empty database`() {
        scraper.scrape("null", sourceUrl).shouldBeEmpty()
    }

    @Test
    fun `scrape skips an act without a date`() {
        val json = """{"a":{"bandName":"No Date","live":true},"b":{"bandName":"Dated","date":"2026-11-01","live":true}}"""

        scraper.scrape(json, sourceUrl).map { it.title } shouldContainExactly listOf("Dated")
    }
}
