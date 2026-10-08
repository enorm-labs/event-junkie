package de.norm.events.scraper.kesselhaus

import de.norm.events.event.EventType
import de.norm.events.scraper.buildArtistsForEventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class KesselhausCalendarScraperTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val url = "https://www.kesselhaus.net/de/calendar"
    private val calendar = Jsoup.parse(fixture("kesselhaus-calendar.html"), url)
    private val kesselhaus = KesselhausCalendarScraper(KesselhausRoom.KESSELHAUS, clock)
    private val maschinenhaus = KesselhausCalendarScraper(KesselhausRoom.MASCHINENHAUS, clock)

    @Test
    fun `keeps each room's upcoming events and drops the other venues and the past months`() {
        val big = kesselhaus.scrape(calendar, url)
        val small = maschinenhaus.scrape(calendar, url)

        big shouldHaveSize 49
        small shouldHaveSize 38
        big.first().title shouldBe "Till Reiners' Happy Hour"
        big.first().eventDate shouldBe LocalDate.of(2026, 10, 8)
        big.last().title shouldBe "17 Hippies"
        small.first().title shouldBe "Nobel-Popel: \"Zebrastreifen\""
        (big.map { it.sourceId } intersect small.map { it.sourceId }.toSet()).shouldBeEmpty()
        big.all { it.sourceId.startsWith("kesselhaus:") } shouldBe true
        small.all { it.sourceId.startsWith("maschinenhaus:") } shouldBe true
    }

    @Test
    fun `reads a concert with its Berlin start, prices, ticket link and cover`() {
        val event = kesselhaus.scrape(calendar, url).last { it.title == "17 Hippies" }

        event.eventType shouldBe EventType.CONCERT.name
        event.eventDate shouldBe LocalDate.of(2026, 12, 30)
        event.startTime shouldBe LocalTime.of(20, 30)
        event.sourceUrl shouldBe "https://www.kesselhaus.net/de/calendar/-OqkoMBienOKIwYBn5d_"
        event.sourceId shouldBe "kesselhaus:-OqkoMBienOKIwYBn5d_"
        event.ticketUrl.shouldNotBeNull() shouldStartWith "https://"
        event.pricePresale.shouldNotBeNull()
        event.imageUrl.shouldNotBeNull() shouldStartWith "https://firebasestorage.googleapis.com/"
        event.artists.map { it.name } shouldBe listOf("17 Hippies")
    }

    @Test
    fun `reads a month's first card past its separator, and types a party-tagged concert as a party`() {
        val events = kesselhaus.scrape(calendar, url)

        events.first { it.title == "Zsá Zsá" }.eventType shouldBe EventType.CONCERT.name
        val birthday = events.first { it.title.startsWith("Thomas Lizzara") }
        birthday.eventType shouldBe EventType.PARTY.name
        birthday.artists.shouldBeEmpty()
    }

    @Test
    fun `marks a move and keeps the subtitle that names the new venue`() {
        val event = kesselhaus.scrape(calendar, url).first { it.title == "Ruel" }

        event.status shouldBe "RELOCATED"
        event.statusNote shouldBe "wird ins Hole 44 verlegt"
    }

    @Test
    fun `takes a support act from the subtitle`() {
        val events = kesselhaus.scrape(calendar, url) + maschinenhaus.scrape(calendar, url)
        val withSupport = events.filter { it.subtitle?.startsWith("Support:") == true }

        withSupport.shouldNotBeEmpty()
        withSupport.forEach { event -> event.artists.any { it.role == "SUPPORT" } shouldBe true }
    }

    @Test
    fun `bills no programme name, and bills the acts a tribute's subtitle lists after mit`() {
        val events = kesselhaus.scrape(calendar, url)

        events.first { it.title == "41. Berliner Jazztreff" }.artists.shouldBeEmpty()
        events.first { it.title == "Weihnachtssingen im Kiez" }.artists.shouldBeEmpty()
        val tribute = events.first { it.title.startsWith("Tribute concert: In memory of Lemmy") }
        tribute.artists.map { it.name to it.role } shouldBe listOf("Motörblast" to "HEADLINER", "Nitrogods" to "HEADLINER")
    }

    @Test
    fun `leaves a festival's subtitle line-up and a named act's mit guest unbilled`() {
        // Both events are past at the fixture's capture, so the clock goes back to the window's first month.
        val august = Clock.fixed(Instant.parse("2026-08-01T08:00:00Z"), ZoneId.of("Europe/Berlin"))
        val events = KesselhausRoom.entries.flatMap { KesselhausCalendarScraper(it, august).scrape(calendar, url) }

        events.first { it.title == "The Sound of Courage - Das Festival" }.artists.shouldBeEmpty()
        events.first { it.title.startsWith("Dying Phoenix") }.artists.map { it.name } shouldBe listOf("Dying Phoenix")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "' Jonathan Blues Band & Gäste' | Jonathan Blues Band",
            "Jonathan Blues Band und Gäste | Jonathan Blues Band",
            "Jonathan Blues Band & Gaeste | Jonathan Blues Band",
            "JONATHAN BLUES BAND & GÄSTE | JONATHAN BLUES BAND",
            "jonathan blues band UND gäste | jonathan blues band",
            "Jonathan Blues Band & Friends | Jonathan Blues Band",
            "The Original Prenzlauer Berg Rhythm and Blues Revival Band & Gäste | The Original Prenzlauer Berg Rhythm and Blues Revival Band",
            "Ina & The Blue Notes & Friends | Ina & The Blue Notes"
        ]
    )
    fun `bills the one act a programme's subtitle names before its guests`(
        subtitle: String,
        act: String
    ) {
        val event = scrapeOne(title = "30. Traditioneller Neujahrs-Blues", subtitle = subtitle)

        event.artists.map { it.name to it.role } shouldBe listOf(act to "HEADLINER")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Kein Zurück Tour",
            "Berliner Jazztreff – Treffpunkt für die junge Jazzszene",
            "Gäste",
            "& Friends",
            "Jonathan Blues Band & Gästeliste"
        ]
    )
    fun `bills nothing from a tour or tagline subtitle under a programme name`(subtitle: String) {
        scrapeOne(title = "30. Traditioneller Neujahrs-Blues", subtitle = subtitle).artists.shouldBeEmpty()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "The Hamburg Blues Band & Friends | feat. Chris Farlowe",
            "Happy Dog Brown | Tim Kutschfreund & Friends",
            "Jonathan Blues Band | Jonathan Blues Band & Gäste"
        ]
    )
    fun `keeps the bill of a title that names an act`(
        title: String,
        subtitle: String
    ) {
        val event = scrapeOne(title = title, subtitle = subtitle)

        event.artists.shouldNotBeEmpty()
        event.artists shouldBe buildArtistsForEventType(title, subtitle, EventType.CONCERT.name)
    }

    @Test
    fun `steps to the window after the last month shown and stops after an empty one`() {
        kesselhaus.nextPage(calendar, url) shouldBe "https://www.kesselhaus.net/de/calendar?part=2027-03"
        val last = Jsoup.parse(fixture("kesselhaus-calendar-2027-10.html"), "$url?part=2027-10")
        kesselhaus.nextPage(last, "$url?part=2027-10") shouldBe "https://www.kesselhaus.net/de/calendar?part=2027-12"
        kesselhaus.scrape(last, "$url?part=2027-10").single().title shouldBe "Manolito Simonet y su Trabuco"
        kesselhaus.nextPage(Jsoup.parse("<html><body></body></html>", url), url).shouldBeNull()
    }

    @Test
    fun `adds the text and the full-size image from the event's own page`() {
        val event = kesselhaus.scrape(calendar, url).first()
        val page = Jsoup.parse(fixture("kesselhaus-event-dota.html"), event.sourceUrl)
        val dota = event.copy(sourceUrl = "https://www.kesselhaus.net/de/calendar/-Ors0wnvA9pHewc90PM4")

        val enriched = kesselhaus.enrich(dota, page).shouldNotBeNull()

        enriched.description.shouldNotBeNull() shouldStartWith "Dota ist wieder da, mit neuen Songs"
        enriched.imageUrl.shouldNotBeNull() shouldStartWith "https://firebasestorage.googleapis.com/"
    }

    @Test
    fun `strips the CMS markup and the sponsor heading, and reads the doors time the text states`() {
        val event = kesselhaus.scrape(calendar, url).first { it.title == "Seksendört - Live 2026" }
        val page = Jsoup.parse(fixture("kesselhaus-event-seksendort.html"), event.sourceUrl)

        val enriched = kesselhaus.enrich(event, page).shouldNotBeNull()

        val text = enriched.description.shouldNotBeNull()
        text shouldStartWith "Einlass: 20:00 Uhr | Beginn: 21:00 Uhr"
        text shouldNotContain "<"
        text shouldNotContain "####"
        text shouldNotContain "Präsentiert von"
        enriched.startTime shouldBe LocalTime.of(21, 0)
        enriched.doorsTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `returns nothing for a page without the transfer state`() {
        kesselhaus.scrape(Jsoup.parse("<html><body></body></html>", url), url).shouldBeEmpty()
    }

    /** One concert card on a calendar page of its own, with the transfer state the CMS writes, `&`-escaped as `&a;`. */
    private fun scrapeOne(
        title: String,
        subtitle: String
    ) = kesselhaus
        .scrape(Jsoup.parse(calendarPage(title, subtitle), url), url)
        .single()

    private fun calendarPage(
        title: String,
        subtitle: String
    ): String {
        val base = """{"venue":"/venues//kesselhaus","start":"2027-01-09T19:00:00.000Z","topics":["/categories//event-topics//subs//concerts"]}"""
        val text = """{"title":"$title","subtitle":"$subtitle"}"""
        val state = """{"store.x.doc:/events//e1/meta:base/1":$base,"store.x.doc:/events//e1/meta:de/1":$text}"""
        val escaped = state.replace("&", "&a;").replace("\"", "&q;")
        return """
            <div class="item" data-id="e1" data-use="default" data-part="2027-01"><span class="category">Konzert</span></div>
            <script id="serverApp-state" type="application/json">$escaped</script>
            """.trimIndent()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/kesselhaus/$name")!!
            .bufferedReader()
            .readText()
}
