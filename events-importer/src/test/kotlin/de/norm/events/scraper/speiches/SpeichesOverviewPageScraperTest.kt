package de.norm.events.scraper.speiches

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
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

    private fun concertRow(text: String): ScrapedEvent {
        val link = "javascript:va_info1(\"veranstaltungen_info.php?auswahl_lfdnr=48310\")"
        val html =
            "<table><tr><td><a href='$link'>Sa&nbsp;24.10.</a></td><td><a href='$link'>20.00</a></td>" +
                "<td><a href='$link'>Konzert</a><br>Ort: Speiche</td><td></td><td><a href='$link'>$text</a></td></tr></table>"
        return SpeichesOverviewPageScraper(clock).scrape(Jsoup.parse(html, baseUrl), baseUrl).single()
    }

    private fun actsOfRow(text: String): List<String> = concertRow(text).artists.map { it.name }

    @Test
    fun `keeps a name written all lower case whole`() {
        // Event 12518.
        actsOfRow("glass and stone - famous rock cover and best own blues mit Heinz Glass und Edward Stone<br>Eintritt frei") shouldContainExactly
            listOf("glass and stone")
    }

    @Test
    fun `a Lesung label makes a Konzert row a reading that bills its authors`() {
        // Event 12519.
        val reading = concertRow("Lesung: Martin Puttke, Bastienne Voss - Vater und Tochter lesen aus ihren Debüts -Vier Irre - auf verschlungenen Wegen-")

        reading.eventType shouldBe EventType.READING.name
        reading.artists.map { it.name } shouldContainExactly listOf("Martin Puttke", "Bastienne Voss")
    }

    @Test
    fun `cuts a genre list glued to the last act or its country tag`() {
        actsOfRow("Emiliano Degl´Innocenti & Tom Blacksmith (ITA/D )- Blues, Soul, Rock´n´Roll - Eintritt frei") shouldContainExactly
            listOf("Emiliano Degl'Innocenti", "Tom Blacksmith")
        actsOfRow("Kurt Buschmann ( USA / D )- Blues - Eintritt frei") shouldContainExactly listOf("Kurt Buschmann")
        actsOfRow("Kurt Buschmann- Blues, Folk") shouldContainExactly listOf("Kurt Buschmann")
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

        on("2026-10-12", "19:00").subtitle shouldBe "mit Martin Rose + Very Special Guest, Musicians Welcome!"
        shallaras.genre shouldBe "Rock'n'Roll"
    }

    @Test
    fun `a rockradio broadcast is OTHER and names no act`() {
        val talk = on("2026-10-11", "16:00")
        talk.title shouldBe "Wahl zum Abgeordnetenhaus von Berlin"
        talk.eventType shouldBe EventType.OTHER.name
        talk.artists.shouldBeEmpty()
    }

    @Test
    fun `a rockradio broadcast that names an interview is a reading`() {
        val link = "javascript:va_info1(\"veranstaltungen_info.php?auswahl_lfdnr=48311\")"
        val html =
            "<table><tr><td><a href='$link'>Di&nbsp;27.10.</a></td><td><a href='$link'>19.00</a></td>" +
                "<td><a href='$link'>rockradio.de-live</a><br>Ort: Speiche</td><td></td>" +
                "<td><a href='$link'>im Gespräch mit Lutz Schramm, RadioMacher</a></td></tr></table>"

        val talk = SpeichesOverviewPageScraper(clock).scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        talk.eventType shouldBe EventType.READING.name
        talk.artists.shouldBeEmpty()
    }

    @Test
    fun `reads a year-end row into the next year by its weekday`() {
        on("2027-01-26", "19:00").title shouldBe "Speiches Open Stage"
    }

    @Test
    fun `cuts a style list glued with commas out of the subtitle and keeps it for the genre`() {
        // Event 13037.
        val stage =
            concertRow("Speiches Open Stage - mit Martin Rose + Very Special Guest, Musicians Welcome! Folk,Blues,Soul,Jazz,Country,SingerSongwriter...")

        stage.subtitle shouldBe "mit Martin Rose + Very Special Guest, Musicians Welcome!"
        stage.genre?.contains("Blues") shouldBe true
        concertRow("Proud Fools - Americana, Country, Folk, Blues, Rock").subtitle shouldBe "Americana, Country, Folk, Blues, Rock"
    }

    @Test
    fun `bills the name of a tribute band, not the act it plays`() {
        // Event 13039.
        val row = concertRow("Van Morrison Tribute Band  Madame George - Eintritt immer frei")

        row.title shouldBe "Van Morrison Tribute Band Madame George"
        row.artists.map { it.name } shouldContainExactly listOf("Madame George")
        actsOfRow("Dirty Work - The Real Rolling Stones Tribute Band unplugged Show") shouldContainExactly listOf("Dirty Work")
    }
}
