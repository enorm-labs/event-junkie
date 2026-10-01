package de.norm.events.scraper.neuezukunft

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for [NeueZukunftApiScraper].
 *
 * Parses a saved snapshot of Neue Zukunft's Elfsight Event Calendar boot response
 * (`core.service.elfsight.com/p/boot/?w=<widgetId>`) for deterministic, offline-safe
 * testing without HTTP fetching. One-off entries come back as-is, past ones included; dropping
 * past-dated events is the persistence layer's concern (`EventUpsertService`) and is tested there.
 * Only the recurring entries depend on the date, so the clock is fixed at 2026-10-01.
 */
class NeueZukunftApiScraperTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val scraper = NeueZukunftApiScraper(Clock.fixed(LocalDate.of(2026, 10, 1).atStartOfDay(berlin).toInstant(), berlin))

    private val rawJson: String by lazy {
        javaClass.classLoader
            .getResourceAsStream("scraper/neuezukunft/neuezukunft-api.json")!!
            .bufferedReader()
            .readText()
    }

    private val events: List<ScrapedEvent> by lazy { scraper.scrape(rawJson) }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `parses every event in the widget response`() {
        // 40 one-off entries, and 4 monthly series with 6 occurrences each in the 26-week horizon.
        events shouldHaveSize 64
    }

    @Test
    fun `expands each monthly series onto the nth Wednesday the widget renders`() {
        // The dates the live widget showed for October and November 2026, matching the descriptions:
        // "every 1st and 3rd Wednesday" and "every 2nd and 4th Wednesday" (#333).
        fun dates(title: String) =
            events
                .filter { it.title == title }
                .map { it.eventDate }
                .filter { it < LocalDate.of(2026, 12, 1) }
                .sorted()

        dates("Jazz After Dark") shouldContainExactly
            listOf(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 21), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 18))
        dates("Future Bash Reloaded") shouldContainExactly
            listOf(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 25))
    }

    @Test
    fun `gives a series occurrence a dated sourceId and drops the past ones`() {
        val first = events.filter { it.title == "Jazz After Dark" }.minBy { it.eventDate }

        first.sourceId shouldBe "neue_zukunft:161c9267-49e6-43a1-9bf9-8bb6805d10ca-2026-10-07"
        first.startTime shouldBe LocalTime.of(20, 30)
        events.filter { it.title == "Future Bash Reloaded" }.map { it.eventDate }.last() shouldBe LocalDate.of(2027, 3, 24)
    }

    @Test
    fun `maps all fields of a representative concert`() {
        val backengrillen = event("neue_zukunft:5f68aab9-d858-4cf9-894a-79aa287f5159")
        backengrillen.title shouldBe "Backengrillen + Stinking Lizaveta"
        backengrillen.eventType shouldBe "CONCERT"
        backengrillen.eventDate shouldBe LocalDate.of(2026, 7, 8)
        backengrillen.startTime shouldBe LocalTime.of(19, 0)
        backengrillen.ticketUrl shouldBe
            "https://www.eventbrite.de/e/usu-pres-backengrillen-refused-members-gustafsson-stinking-lizaveta-tickets-1985279206549"
        backengrillen.soldOut shouldBe false
        backengrillen.status shouldBe "SCHEDULED"
        backengrillen.imageUrl.shouldBeNull()
        backengrillen.sourceUrl shouldBe "https://neue-zukunft.org/konzerte.html"
        backengrillen.artists shouldContainExactly
            listOf(
                ScrapedArtist("Backengrillen", "HEADLINER", titleDerived = true),
                ScrapedArtist("Stinking Lizaveta", "HEADLINER", titleDerived = true)
            )
    }

    @Test
    fun `flattens the HTML description into paragraph-separated text`() {
        val description = event("neue_zukunft:5f68aab9-d858-4cf9-894a-79aa287f5159").description
        description.shouldStartWith("Unlimited Sonic Use presents:")
        // The nested <a> text survives and paragraph breaks become newlines, not run-together text.
        description shouldContain "https://backengrillen.bandcamp.com"
        description shouldContain "\n"
    }

    @Test
    fun `captures the sold-out flag and leaves no ticket URL for a Sold Out marker`() {
        val deadMoon = event("neue_zukunft:44ce48df-2ab5-46f0-bb23-87f27de8167e")
        deadMoon.soldOut shouldBe true
        // The "Sold Out!" action carries an empty link, so no ticket URL is stored.
        deadMoon.ticketUrl.shouldBeNull()
        deadMoon.status shouldBe "SCHEDULED"
    }

    @Test
    fun `classifies a festival title as FESTIVAL and extracts no headliner from it`() {
        val festival = event("neue_zukunft:d8796ac9-00d7-47fa-a651-1b2a6a217e8f")
        festival.title shouldBe "Festival Entre Trópicos"
        festival.eventType shouldBe "FESTIVAL"
        festival.artists.shouldBeEmpty()
        // A festival still keeps its ticket link.
        festival.ticketUrl shouldContain "dice.fm"
    }

    @Test
    fun `reads the cover image URL and splits a co-billed title into headliners`() {
        val tvod = event("neue_zukunft:1191ae02-f408-4e6b-89c3-151c6bb995a1")
        tvod.imageUrl.shouldStartWith("https://dice-media.imgix.net/attachments/2026-03-16/")
        tvod.artists shouldContainExactly
            listOf(
                ScrapedArtist("TVOD", "HEADLINER", titleDerived = true),
                ScrapedArtist("Twiggy", "HEADLINER", titleDerived = true)
            )
    }

    @Test
    fun `leaves optional fields null when the event omits them`() {
        val minimal = event("neue_zukunft:be410164-693a-475c-86fc-1aa49db2ff73")
        minimal.description.shouldBeNull()
        minimal.imageUrl.shouldBeNull()
        minimal.ticketUrl.shouldBeNull()
        minimal.soldOut shouldBe false
        minimal.artists shouldContainExactly
            listOf(
                ScrapedArtist("Daniela Ljungsberg", "HEADLINER", titleDerived = true),
                ScrapedArtist("Shaul Dahan", "HEADLINER", titleDerived = true)
            )
    }

    @Test
    fun `builds a stable sourceId prefixed from the widget event id`() {
        val backengrillen = event("neue_zukunft:5f68aab9-d858-4cf9-894a-79aa287f5159")
        backengrillen.sourceId shouldBe "neue_zukunft:5f68aab9-d858-4cf9-894a-79aa287f5159"
    }

    @Test
    fun `returns an empty list for a payload without widgets`() {
        scraper.scrape("""{"status":1,"data":{}}""").shouldBeEmpty()
    }

    @Test
    fun `returns an empty list for unparseable JSON`() {
        scraper.scrape("not json at all").shouldBeEmpty()
    }

    @Test
    fun `reads a free-entry button as the price note and a gallery image as the fallback poster`() {
        // The Herbstfest node from the widget on 2026-09-30, trimmed to the fields the scraper reads.
        val json =
            """
            {"data":{"widgets":{"w":{"data":{"settings":{"events":[{
              "id":"0e0d296d-710e-4dc4-9b0e-809fec5d5f02","name":"Herbstfest!",
              "start":{"type":"datetime","date":"2026-10-02","time":"16:00"},
              "actions":[{"type":"link","text":"Eintritt frei!","link":{"type":"url","value":""}}],
              "coverImage":null,
              "images":[{"url":"https://files.elfsightcdn.com/eafe4a4d/9dbf9c61/Herbstsfest-2026.jpg"}]
            }]}}}}}}
            """.trimIndent()
        val herbstfest = scraper.scrape(json).single()

        herbstfest.priceNote shouldBe "Eintritt frei!"
        herbstfest.ticketUrl.shouldBeNull()
        herbstfest.imageUrl shouldBe "https://files.elfsightcdn.com/eafe4a4d/9dbf9c61/Herbstsfest-2026.jpg"
        herbstfest.toEventEntity(venueId = 1L, venueSlug = "neue-zukunft", eventSourceId = 1L).free shouldBe true
    }
}
