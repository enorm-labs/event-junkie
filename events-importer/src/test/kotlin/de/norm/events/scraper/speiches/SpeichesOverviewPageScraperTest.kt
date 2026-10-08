package de.norm.events.scraper.speiches

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** The fixture was saved on 2026-10-08; the clock stands on that day. */
class SpeichesOverviewPageScraperTest {
    private val baseUrl = "http://www.rockradio.de/rr_termine_speiche_werbung_termine_raumerstr.php"
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC)

    private val events by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/speiches/speiches-overview.html")!!
                .bufferedReader()
                .readText()
        SpeichesOverviewPageScraper(clock).scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun on(
        date: String,
        time: String = "20:00"
    ) = events.single { it.eventDate == LocalDate.parse(date) && it.startTime == LocalTime.parse(time) }

    @Test
    fun `reads every dated night at the pub, and skips the Club23 rows and the 01-01 placeholders`() {
        // 46 rows: 3 at Club23, and 3 placeholders dated 01.01. that fit no weekday.
        events shouldHaveSize 40
        events.map { it.eventDate } shouldNotContain LocalDate.of(2027, 1, 1)
        events.map { it.eventDate.year }.toSet() shouldBe setOf(2026, 2027)
    }

    @Test
    fun `reads a concert row`() {
        val event = on("2026-10-09")

        event.title shouldBe "Torsten Turinsky"
        event.subtitle shouldBe "Blues & Roots auf 8-Saiter u. Zigarrenkisten- Gitarren"
        event.eventType shouldBe EventType.CONCERT.name
        event.genre shouldBe "Blues"
        event.free shouldBe true
        event.imageUrl shouldBe "http://www.rockradio.de/images/torsten-turinsky_2025.jpg"
        event.sourceUrl shouldBe "http://www.rockradio.de/veranstaltungen_info.php?auswahl_lfdnr=48298"
        event.sourceId shouldBe "speiches:48298"
        event.artists.map { it.name } shouldContainExactly listOf("Torsten Turinsky")
    }

    @Test
    fun `cuts instrument notes and country tags off the names`() {
        on("2026-10-10").artists.map { it.name } shouldContainExactly listOf("Marcos Coll", "Remy Bankyln")
        on("2026-11-01", "19:59").artists.map { it.name } shouldContainExactly listOf("Emiliano Juarez", "Dai Gallo", "Marcos Coll")
        on("2026-10-31").artists.map { it.name } shouldContainExactly listOf("Rico Mc Clarrin & The Grateful Natives")
        on("2026-10-16").artists.map { it.name } shouldContainExactly listOf("The Shallaras")
        on("2026-10-20", "18:00").artists.map { it.name } shouldContainExactly listOf("Kurt Buschmann", "Proud Fools")
    }

    @Test
    fun `a house series names its band before spielt, and the repeat note leaves the subtitle`() {
        val jazz = on("2026-10-11")
        jazz.title shouldBe "Jazz am Sonntag"
        jazz.subtitle shouldBe "Roamer Street Rag Band spielt Oldtime Jazz, Ragtime, Swing"
        jazz.artists.map { it.name } shouldContainExactly listOf("Roamer Street Rag Band")

        on("2026-10-19", "19:00").artists.shouldBeEmpty()
    }

    @Test
    fun `cuts an entry note inside a segment, and folds the venue's acute apostrophes`() {
        val shallaras = on("2026-10-16")
        shallaras.subtitle shouldBe "50's-60's Rock'n'roll and Rhythm'n'Blues"
        shallaras.free shouldBe true

        on("2026-10-12", "19:00").subtitle shouldBe "mit Martin Rose + Very Special Guest, Musicians Welcome! Folk,Blues,Soul,Jazz,Country,SingerSongwriter"
        // The vocabulary has no rock'n'roll; the house genre fills the night that names nothing else.
        shallaras.genre shouldBe null
    }

    @Test
    fun `a rockradio broadcast is OTHER and names no act`() {
        val talk = on("2026-10-11", "16:00")
        talk.title shouldBe "Wahl zum Abgeordnetenhaus von Berlin"
        talk.eventType shouldBe EventType.OTHER.name
        talk.artists.shouldBeEmpty()
    }

    @Test
    fun `reads a year-end row into the next year by its weekday`() {
        on("2027-01-26", "19:00").title shouldBe "Speiches Open Stage"
    }
}
