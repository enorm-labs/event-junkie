package de.norm.events.scraper.hole44

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [Hole44OverviewPageScraper].
 *
 * Uses a real `/events/` snapshot with a representative mix of `li.event-item`
 * blocks: a plain concert, a concert with a support act and multiple genre tags,
 * a relocated show carrying a `.changes` note, and an informational "Zusatzshow"
 * note that must **not** be read as a status change.
 */
class Hole44OverviewPageScraperTest {
    private val scraper = Hole44OverviewPageScraper()
    private val baseUrl = "https://hole-berlin.de/events/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/hole44/hole44-overview.html")!!
                .bufferedReader()
                .readText()
        events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceIdSuffix: String): ScrapedEvent = events.first { it.sourceId == "hole44:$sourceIdSuffix" }

    @Test
    fun `extracts every event item from the fixture`() {
        events shouldHaveSize 74
    }

    @Test
    fun `parses date, start time, genre and detail URL for a plain concert`() {
        val kang = event("2026-07-27-kang-yuchan")
        kang.title shouldBe "KANG YUCHAN"
        kang.eventType shouldBe "CONCERT"
        kang.eventDate shouldBe LocalDate.of(2026, 7, 27)
        kang.startTime shouldBe LocalTime.of(19, 0)
        // The overview never renders a doors time — the detail page supplies it.
        kang.doorsTime.shouldBeNull()
        kang.genre shouldBe "K-Pop"
        kang.status shouldBe "SCHEDULED"
        kang.sourceUrl shouldBe "https://hole-berlin.de/event/2026-07-27-kang-yuchan/"
        kang.artists shouldContainExactly listOf(ScrapedArtist("KANG YUCHAN", "HEADLINER"))
    }

    @Test
    fun `extracts the headliner, support act and joined genres for a concert`() {
        val municipal = event("2026-08-02-municipal-waste")
        municipal.subtitle shouldBe "+ Support: Battlecreek"
        municipal.genre shouldBe "crossover trash, Hardcore-Punk, Trash Metal"
        municipal.startTime shouldBe LocalTime.of(20, 0)
        municipal.artists shouldContainExactly
            listOf(
                ScrapedArtist("Municipal Waste", "HEADLINER"),
                ScrapedArtist("Battlecreek", "SUPPORT")
            )
    }

    @Test
    fun `flags a relocated event from its changes note, and carries the note for the boundary to read the direction`() {
        event("2026-11-20-luke-combs-uk-tribute").status shouldBe "RELOCATED"
        event("2026-10-29-kate-ryan").status shouldBe "RELOCATED"
        event("2026-10-29-kate-ryan").statusNote shouldBe "Achtung: Die Show wird vom Huxleys ins Hole44 verlegt!"
        // Kate Ryan arrived here; Luke Combs and Killerpilze left (#1551).
        entity("2026-10-29-kate-ryan").status shouldBe "SCHEDULED"
        entity("2026-11-20-luke-combs-uk-tribute").relocatedTo shouldBe "Metropol"
        entity("2027-01-29-killerpilze").relocatedTo shouldBe "Huxleys"
    }

    private fun entity(sourceIdSuffix: String) = event(sourceIdSuffix).toEventEntity(venueId = 1L, venueSlug = "hole-44", eventSourceId = 1L)

    @Test
    fun `does not treat an informational Zusatzshow note as a status change`() {
        // The "Zusatzshow" (extra show) note shares the `.changes` markup a relocation uses,
        // but carries no cancellation/relocation keyword, so the event stays SCHEDULED.
        event("2026-10-14-bangerfabrique").status shouldBe "SCHEDULED"
    }
}
