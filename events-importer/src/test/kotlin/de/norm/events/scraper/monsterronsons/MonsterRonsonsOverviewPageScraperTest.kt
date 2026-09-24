package de.norm.events.scraper.monsterronsons

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
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

/**
 * Unit tests for [MonsterRonsonsOverviewPageScraper].
 *
 * Parses a static snapshot of the `/events` page captured on 2026-08-06, whose first page runs
 * 6–17 August 2026. The clock is pinned to the capture date so the year inferred from each card's
 * weekday is deterministic, and so no card falls foul of the past-event cutoff.
 */
class MonsterRonsonsOverviewPageScraperTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-06T09:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = MonsterRonsonsOverviewPageScraper(clock)
    private val baseUrl = "https://www.karaokemonster.de/events"

    private val events: List<ScrapedEvent> by lazy { scrape("monsterronsons-overview.html") }

    private fun scrape(fixture: String): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/monsterronsons/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `discovers every card on the first page except the closure notice`() {
        // 12 cards cover 6–17 Aug; the 16 Aug card is a "CLOSED" notice, not an event.
        events shouldHaveSize 11
        events.map { it.title } shouldContain "BOXHOPPING!"
    }

    @Test
    fun `drops the closure card so no detail fetch is spent on it`() {
        // 16 Aug is the venue's dark day: the card reads "CLOSED" with no time.
        events.any { it.title.trim().equals("CLOSED", ignoreCase = true) } shouldBe false
        events.any { it.eventDate == LocalDate.of(2026, 8, 16) } shouldBe false
    }

    @Test
    fun `maps a fully populated card`() {
        val opener = event("monster_ronsons:2026-08-06-sing-with-fauxpas-2")
        opener.title shouldBe "SING WITH IVANKA TRAMP"
        opener.subtitle shouldBe "Sing on stage!"
        opener.eventType shouldBe EventType.PARTY.name
        opener.eventDate shouldBe LocalDate.of(2026, 8, 6)
        opener.startTime shouldBe LocalTime.of(20, 0)
        opener.sourceUrl shouldBe "https://www.karaokemonster.de/posts/sing-with-fauxpas-2"
        opener.imageUrl!! shouldStartWith "https://cdn.prod.website-files.com/"
        opener.artists.map { it.name } shouldBe listOf("IVANKA TRAMP")
        opener.artists.map { it.role } shouldBe listOf("DJ")
        // The listing states no price and no doors time; both come from the night page or not at all.
        opener.priceBoxOffice.shouldBeNull()
        opener.doorsTime.shouldBeNull()
    }

    @Test
    fun `infers the year from the stated weekday`() {
        // The card says "Thu / 6 Aug" with no year. 6 August falls on a Thursday in 2026 but not in
        // 2025 or 2027, so the weekday alone pins the year — an assumed "current year" would be
        // right here by luck and wrong across a New Year boundary.
        event("monster_ronsons:2026-08-06-sing-with-fauxpas-2").eventDate shouldBe LocalDate.of(2026, 8, 6)
        event("monster_ronsons:2026-08-17-boxhopping-6").eventDate shouldBe LocalDate.of(2026, 8, 17)
    }

    @Test
    fun `dates the whole window consecutively`() {
        events.map { it.eventDate } shouldBe
            (6..17).map { LocalDate.of(2026, 8, it) }.filterNot { it == LocalDate.of(2026, 8, 16) }
    }

    @Test
    fun `splits a co-hosted night into one artist per host`() {
        val coHosted = event("monster_ronsons:2026-08-07-sing-with-wild-thing-mc-uncut-gem-copy")
        coHosted.title shouldBe "SING WITH FERRIS MUELLER & OOZING GLOOP"
        coHosted.artists.map { it.name } shouldBe listOf("FERRIS MUELLER", "OOZING GLOOP")
    }

    @Test
    fun `names no artist for a night titled by format rather than host`() {
        // "BOXHOPPING!" is the venue's roaming-box format; nobody is billed in the title.
        event("monster_ronsons:2026-08-10-boxhopping-7").artists.shouldBeEmpty()
    }

    @Test
    fun `builds sourceId from date and slug because the CMS recycles slugs`() {
        // Two BOXHOPPING nights in one window resolve to different CMS entries, and a recycled entry
        // reappears on a later date — date + slug keeps each night its own row.
        event("monster_ronsons:2026-08-10-boxhopping-7").eventDate shouldBe LocalDate.of(2026, 8, 10)
        event("monster_ronsons:2026-08-17-boxhopping-6").eventDate shouldBe LocalDate.of(2026, 8, 17)
    }

    @Test
    fun `returns nothing for a page with no cards`() {
        val empty = scraper.scrape(Jsoup.parse("<html><body><div class='grid-container'></div></body></html>", baseUrl), baseUrl)
        empty.shouldBeEmpty()
    }

    @Test
    fun `drops a card whose date cannot be parsed rather than guessing one`() {
        val html =
            """
            <div class="grid-container">
              <div class="grid-item">
                <a href="/posts/mystery-night" class="w-inline-block"><img class="grid-img" src="https://example.test/p.jpg"/></a>
                <h3 class="event-overview-hp-head">SING WITH NOBODY</h3>
                <div class="when-time-parent">
                  <div class="when-child"><div class="text-block-9 link">Thu</div></div>
                  <div class="when-child"><div class="date-text link">someday</div></div>
                  <div class="when-child last"><div class="text-block-8 link">20:00</div></div>
                </div>
              </div>
            </div>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `drops a card dated before today`() {
        val html =
            """
            <div class="grid-container">
              <div class="grid-item">
                <a href="/posts/yesterday" class="w-inline-block"><img class="grid-img" src="https://example.test/p.jpg"/></a>
                <h3 class="event-overview-hp-head">SING WITH LAST NIGHT</h3>
                <div class="when-time-parent">
                  <div class="when-child"><div class="text-block-9 link">Wed</div></div>
                  <div class="when-child"><div class="date-text link">5 Aug</div></div>
                  <div class="when-child last"><div class="text-block-8 link">20:00</div></div>
                </div>
              </div>
            </div>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).shouldBeEmpty()
    }
}
