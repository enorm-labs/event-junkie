package de.norm.events.scraper.humboldthain

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for [HumboldthainApiScraper], parsing the saved Elfsight widget boot response.
 *
 * The scraper derives its recurrence horizon from "today", so every test runs against a fixed
 * clock pinned to the day the fixture was captured (2026-08-01) to stay deterministic.
 */
class HumboldthainApiScraperTest {
    private val clock: Clock = Clock.fixed(LocalDate.of(2026, 8, 1).atStartOfDay(BERLIN).toInstant(), BERLIN)
    private val scraper = HumboldthainApiScraper(clock)

    private val fixtureJson: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/humboldthain/humboldthain-api.json")!!
            .bufferedReader()
            .readText()

    private val events: List<ScrapedEvent> by lazy { scraper.scrape(fixtureJson) }

    private fun eventTitled(title: String): ScrapedEvent = events.first { it.title == title }

    @Test
    fun `scrape returns every calendar entry plus the resident night's occurrences`() {
        // 77 calendar entries: 76 one-off nights, plus the weekly resident night expanded into
        // its 4 remaining Tuesdays (2026-08-04 … 2026-08-25, the rule ending 2026-08-27).
        events shouldHaveSize 80
    }

    @Test
    fun `scrape extracts every field of a fully populated party`() {
        val event = eventTitled("WÜSTuWILD X NICE TRIES BERLIN")

        event.eventDate shouldBe LocalDate.of(2026, 8, 21)
        event.startTime shouldBe LocalTime.of(23, 0)
        event.eventType shouldBe EventType.PARTY.name
        event.sourceId shouldBe "humboldthain:d1d36feb-ce08-48c8-9b93-5623834d630a-2026-08-21"
        event.sourceUrl shouldBe "https://www.humboldthain.com/"
        event.imageUrl.shouldNotBeNull() shouldStartWith "https://files.elfsightcdn.com/"
        event.description.shouldNotBeNull() shouldContain "LINEUP: TBA"
        event.status shouldBe "SCHEDULED"
        event.soldOut shouldBe false
    }

    @Test
    fun `scrape reads the DJ lineup from the description's Resident Advisor artist links`() {
        val event = eventTitled("ELYSION - Vinyl only")

        event.artists.map { it.name } shouldContainExactly
            listOf(
                "Florelle",
                "get no",
                "Nein oh Nein",
                "HiHat",
                "1luu",
                "YOVA",
                "Nanno",
                "daschka",
                "Makinarium",
                "DJ Henk",
                "Krash Cora",
                "LŸBRA",
                "TINOU",
                "Moritz Biebl"
            )
        event.artists.map { it.role }.toSet() shouldBe setOf(ArtistRole.DJ.name)
    }

    @Test
    fun `scrape derives no artists from a night whose lineup is only prose`() {
        // "LINEUP: TBA" and the free-text rosters other nights use are not marked up as links.
        eventTitled("WÜSTuWILD X NICE TRIES BERLIN").artists shouldHaveSize 0
        eventTitled("HUMBI SOMMERFEST").artists shouldHaveSize 0
    }

    @Test
    fun `scrape types a KONZERT-prefixed title as a concert and bills its act`() {
        val event = eventTitled("Heiße Assis")

        event.eventType shouldBe EventType.CONCERT.name
        event.eventDate shouldBe LocalDate.of(2026, 3, 20)
        event.artists.map { it.name } shouldContainExactly listOf("Heiße Assis")
        event.artists.single().role shouldBe ArtistRole.HEADLINER.name
    }

    @Test
    fun `scrape expands the weekly resident night into one event per remaining occurrence`() {
        val residency = events.filter { it.title == "OPEN DECKS & TISCHTENNIS" }

        residency.map { it.eventDate } shouldContainExactly
            listOf(
                LocalDate.of(2026, 8, 4),
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 8, 18),
                LocalDate.of(2026, 8, 25)
            )
        // Each occurrence is its own upsert key: the widget id plus the date it falls on.
        residency.map { it.sourceId } shouldContainExactly
            residency.map { "humboldthain:c4f8439d-da21-4984-8d24-2c93a1ef71f6-${it.eventDate}" }
        residency.map { it.startTime }.toSet() shouldBe setOf(LocalTime.of(18, 0))
    }

    @Test
    fun `scrape takes the ticket link from the widget's own action button`() {
        val event = eventTitled("Anechoic Frühlingsauftakt invites Licious")

        event.ticketUrl shouldBe "https://ra.co/events/2213418"
    }

    @Test
    fun `scrape falls back to a ticket-shop link the venue wrote into the description`() {
        // The widget action is empty; the only shop link is an <a> inside the prose.
        eventTitled("Candyrecords - Bunny Bites Release Party").ticketUrl shouldBe "https://de.ra.co/events/2493487"
        eventTitled("Heiße Assis").ticketUrl.shouldNotBeNull() shouldContain "eventim-light.com"
    }

    @Test
    fun `scrape ignores a Resident Advisor artist profile when looking for a ticket link`() {
        eventTitled("ELYSION - Vinyl only").ticketUrl.shouldBeNull()
    }

    @Test
    fun `scrape publishes no prices, genre or sold-out state as the venue states none`() {
        val event = eventTitled("BSM - Farbwechsel")

        // "--- 13€ Tickets available at the box office ---" is prose, not a structured field.
        event.pricePresale.shouldBeNull()
        event.priceBoxOffice.shouldBeNull()
        event.priceNote.shouldBeNull()
        event.genre.shouldBeNull()
        event.soldOut shouldBe false
    }

    @Test
    fun `scrape returns nothing for a payload with no widgets`() {
        scraper.scrape("""{"status":1,"data":{"widgets":{}}}""") shouldHaveSize 0
    }

    @Test
    fun `scrape returns nothing for an unparseable payload`() {
        scraper.scrape("not json at all") shouldHaveSize 0
    }

    @Test
    fun `scrape skips entries missing an id, a name or a date`() {
        val json =
            """
            {"data":{"widgets":{"w1":{"data":{"settings":{"events":[
              {"name":"No id","start":{"date":"2026-09-01"}},
              {"id":"a","start":{"date":"2026-09-01"}},
              {"id":"b","name":"No date"},
              {"id":"c","name":"Keeper","start":{"date":"2026-09-01","time":"22:00"}}
            ]}}}}}}
            """.trimIndent()

        val parsed = scraper.scrape(json)

        parsed shouldHaveSize 1
        parsed.single().title shouldBe "Keeper"
        parsed.single().sourceId shouldBe "humboldthain:c-2026-09-01"
    }

    @Test
    fun `scrape keeps only the start date of a recurrence rule it cannot expand`() {
        // Guessing at a yearly rule no venue publishes would invent dates, so it contributes its start date alone.
        val json =
            """
            {"data":{"widgets":{"w1":{"data":{"settings":{"events":[
              {"id":"m","name":"Yearly","start":{"date":"2026-09-02","time":"20:30"},
               "repeatPeriod":"custom","repeatFrequency":"yearly","repeatInterval":1,
               "repeatWeeklyOnDays":["we"],"repeatEnds":"never"}
            ]}}}}}}
            """.trimIndent()

        val parsed = scraper.scrape(json)

        parsed shouldHaveSize 1
        parsed.single().eventDate shouldBe LocalDate.of(2026, 9, 2)
    }

    @Test
    fun `scrape bounds an open-ended weekly rule by the rolling horizon`() {
        val json =
            """
            {"data":{"widgets":{"w1":{"data":{"settings":{"events":[
              {"id":"r","name":"Forever","start":{"date":"2026-07-06","time":"20:00"},
               "repeatPeriod":"custom","repeatFrequency":"weekly","repeatInterval":1,
               "repeatWeeklyOnDays":["mo"],"repeatEnds":"never"}
            ]}}}}}}
            """.trimIndent()

        val parsed = scraper.scrape(json)

        // Mondays from today (2026-08-01) to today + 26 weeks (2027-01-30) — the past ones dropped.
        parsed.first().eventDate shouldBe LocalDate.of(2026, 8, 3)
        parsed.last().eventDate shouldBe LocalDate.of(2027, 1, 25)
        parsed shouldHaveSize 26
    }

    @Test
    fun `scrape honours a fortnightly interval, an occurrence cap and skipped dates`() {
        val json =
            """
            {"data":{"widgets":{"w1":{"data":{"settings":{"events":[
              {"id":"f","name":"Fortnightly","start":{"date":"2026-08-04","time":"20:00"},
               "repeatPeriod":"custom","repeatFrequency":"weekly","repeatInterval":2,
               "repeatWeeklyOnDays":["tu"],"repeatEnds":"afterOccurrences","repeatEndsOccurrences":4,
               "exceptions":[{"date":"2026-09-01"}]}
            ]}}}}}}
            """.trimIndent()

        val parsed = scraper.scrape(json)

        // Every second Tuesday, capped at 4 occurrences from the series start, minus the exception.
        parsed.map { it.eventDate } shouldContainExactly
            listOf(
                LocalDate.of(2026, 8, 4),
                LocalDate.of(2026, 8, 18),
                LocalDate.of(2026, 9, 15)
            )
    }

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
