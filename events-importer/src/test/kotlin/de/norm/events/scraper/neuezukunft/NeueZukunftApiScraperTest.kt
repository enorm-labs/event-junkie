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
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [NeueZukunftApiScraper].
 *
 * Parses a saved snapshot of Neue Zukunft's Elfsight Event Calendar boot response
 * (`core.service.elfsight.com/p/boot/?w=<widgetId>`) for deterministic, offline-safe
 * testing without HTTP fetching. The parser is time-independent — it returns the whole
 * calendar as-is; dropping past-dated events is the persistence layer's concern
 * (`EventUpsertService`) and is tested there.
 */
class NeueZukunftApiScraperTest {
    private val scraper = NeueZukunftApiScraper()

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
        events shouldHaveSize 44
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
        backengrillen.sourceUrl shouldBe "https://neue-zukunft.org/"
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
}
