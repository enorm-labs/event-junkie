package de.norm.events.scraper.kesselhaus

import de.norm.events.event.EventType
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

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/kesselhaus/$name")!!
            .bufferedReader()
            .readText()
}
