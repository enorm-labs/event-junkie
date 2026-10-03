package de.norm.events.scraper.modus

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [ModusOverviewPageScraper].
 *
 * Parses a static snapshot of Modus Berlin's `/events` page for deterministic, offline-safe
 * testing without HTTP fetching.
 */
class ModusOverviewPageScraperTest {
    private val scraper = ModusOverviewPageScraper()
    private val baseUrl = "https://modus-berlin.de/events"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/modus/modus-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `discovers every event tile on the listing`() {
        events shouldHaveSize 17
    }

    @Test
    fun `maps a fully populated tile`() {
        val c4rl = event("modus:240926-c4rl")
        c4rl.title shouldBe "c4rl"
        c4rl.eventType shouldBe EventType.CONCERT.name
        c4rl.eventDate shouldBe LocalDate.of(2026, 9, 24)
        c4rl.sourceUrl shouldBe "https://modus-berlin.de/event/240926-c4rl"
        c4rl.imageUrl shouldBe "https://modus-berlin.de/img/6a3e6c11430088.95263972.jpg"
        c4rl.status shouldBe EventStatus.SCHEDULED.name
        c4rl.artists.map { it.name } shouldBe listOf("c4rl")
    }

    @Test
    fun `reads the rendered date rather than the stale date in the slug`() {
        // The slug says 16.04.26, but the show moved: the venue renders 13.04.2027 and says so
        // in the title. Reading the slug would file it 12 months early — and re-minting the
        // sourceId on the move would orphan the row, so the slug stays the identity.
        val lunaSimao = event("modus:160426-LunaSimao")
        lunaSimao.eventDate shouldBe LocalDate.of(2027, 4, 13)
    }

    @Test
    fun `marks a postponed show and strips the note from its title and artist`() {
        val lunaSimao = event("modus:160426-LunaSimao")
        lunaSimao.status shouldBe EventStatus.POSTPONED.name
        lunaSimao.storedTitle() shouldBe "Luna Simao"
        lunaSimao.artists.map { it.name } shouldBe listOf("Luna Simao")
    }

    @Test
    fun `types a poetry slam as a reading rather than a concert`() {
        val slam = event("modus:270826-SpreevomWeizenOpenAir-PoetrySlam-StandUpShow")
        slam.eventType shouldBe EventType.READING.name
        slam.eventDate shouldBe LocalDate.of(2026, 8, 27)
        // An event name, not an act — no artist is minted from it.
        slam.artists.shouldBeEmpty()
    }

    @Test
    fun `types the venue's comedy series and bills nobody from its name`() {
        val html =
            """
            <div class="event-tiles"><div class="event-item">
              <a class="event-link" href="/event/211026-ModusComedy"><figure><img src="/img/comedy.png">
                <figcaption><div>21.10.2026</div><h2>Modus Comedy</h2></figcaption></figure></a>
            </div></div>
            """.trimIndent()

        val comedy = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        comedy.eventType shouldBe EventType.COMEDY.name
        comedy.artists.shouldBeEmpty()
    }

    @Test
    fun `leaves the listing-only fields empty for the detail page to supply`() {
        val c4rl = event("modus:240926-c4rl")
        c4rl.startTime.shouldBeNull()
        c4rl.doorsTime.shouldBeNull()
        c4rl.ticketUrl.shouldBeNull()
        c4rl.description.shouldBeNull()
    }

    @Test
    fun `publishes no prices or sold-out state`() {
        events.forEach {
            it.pricePresale.shouldBeNull()
            it.priceBoxOffice.shouldBeNull()
            it.soldOut shouldBe false
        }
    }

    @Test
    fun `parses every tile into a resolved date`() {
        events.map { it.eventDate }.distinct() shouldHaveSize events.map { it.eventDate }.distinct().size
        events.none { it.eventDate == de.norm.events.scraper.UNRESOLVED_EVENT_DATE } shouldBe true
    }

    @Test
    fun `returns an empty list for a page without event tiles`() {
        val document = Jsoup.parse("<html><body><main class='container'></main></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}
