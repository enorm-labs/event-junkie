package de.norm.events.scraper.velomax

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [VelomaxOverviewPageScraper].
 *
 * Uses a real snapshot of the shared `/events` listing, which interleaves all three halls. The
 * behaviours worth pinning are the filters — each hall must see only its own entries, and the
 * arena's sport fixtures must be excluded entirely (the fixture holds 85 entries of which 32 are
 * sport) — and the session-keyed `sourceId`, which is what lets a run that plays twice in a day
 * store both performances.
 */
class VelomaxOverviewPageScraperTest {
    private val scraper = VelomaxOverviewPageScraper()
    private val baseUrl = "https://www.velomax.de/events"

    private fun scrape(hall: VelomaxHall): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/velomax/velomax-overview.html")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl, hall)
    }

    @Test
    fun `splits the shared listing across the three halls`() {
        // 85 entries on the page: 43 Max-Schmeling-Halle, 32 Velodrom, 10 UFO — minus sport. Every
        // remaining entry is kept, including each session of a run that plays more than once a day.
        scrape(VelomaxHall.MAX_SCHMELING_HALLE) shouldHaveSize 18
        scrape(VelomaxHall.VELODROM) shouldHaveSize 25
        scrape(VelomaxHall.UFO_IM_VELODROM) shouldHaveSize 10
    }

    @Test
    fun `excludes the arena's sport fixtures`() {
        // The model has no SPORT type; 25 of the hall's 43 entries are handball or basketball.
        val msh = scrape(VelomaxHall.MAX_SCHMELING_HALLE)
        msh.none { it.title.contains("Füchse Berlin") } shouldBe true
        msh.none { it.title.contains("BR Volleys") } shouldBe true
        msh.all { it.eventType == "CONCERT" || it.eventType == "SHOW" } shouldBe true
    }

    @Test
    fun `attributes each hall's events to its own source`() {
        scrape(VelomaxHall.VELODROM).all { it.sourceId.startsWith("velodrom:") } shouldBe true
        scrape(VelomaxHall.MAX_SCHMELING_HALLE).all { it.sourceId.startsWith("max_schmeling_halle:") } shouldBe true
        scrape(VelomaxHall.UFO_IM_VELODROM).all { it.sourceId.startsWith("ufo_im_velodrom:") } shouldBe true
    }

    @Test
    fun `parses an entry's date, time and detail link`() {
        val joji = scrape(VelomaxHall.VELODROM).first { it.title == "Joji" }
        joji.eventType shouldBe "CONCERT"
        // Assembled from the separate day / "Aug" / "'26" spans.
        joji.eventDate shouldBe LocalDate.of(2026, 8, 29)
        joji.startTime shouldBe LocalTime.of(20, 0)
        joji.subtitle.shouldNotBeNull()
        joji.sourceUrl shouldBe "https://www.velodrom.de/events/event/joji-velodrom-2026-08-29"
        joji.sourceId shouldBe "velodrom:joji-velodrom-2026-08-29-2000"
        joji.soldOut shouldBe false
    }

    @Test
    fun `types the venue's own show category as a show`() {
        val show = scrape(VelomaxHall.MAX_SCHMELING_HALLE).first { it.sourceId.contains("die-nervigen") }
        show.eventType shouldBe "SHOW"
    }

    @Test
    fun `keeps every session of a run that plays more than once in a day`() {
        // "Disney On Ice" plays six sessions across three days — three of them on 13 March, all
        // three behind the same detail permalink.
        val sessions = scrape(VelomaxHall.VELODROM).filter { it.title.startsWith("Disney On Ice") }
        sessions.map { it.eventDate } shouldContainExactly
            listOf(
                LocalDate.of(2027, 3, 12),
                LocalDate.of(2027, 3, 13),
                LocalDate.of(2027, 3, 13),
                LocalDate.of(2027, 3, 13),
                LocalDate.of(2027, 3, 14),
                LocalDate.of(2027, 3, 14)
            )
    }

    @Test
    fun `gives each session of a day its own sourceId`() {
        // The permalink is one page per show, so without the start-time suffix the three 13 March
        // sessions would share one id and `event.source_id`'s UNIQUE constraint would keep one.
        val sameDay =
            scrape(VelomaxHall.VELODROM)
                .filter { it.title.startsWith("Disney On Ice") && it.eventDate == LocalDate.of(2027, 3, 13) }
        sameDay.map { it.sourceUrl }.distinct() shouldHaveSize 1
        sameDay.map { it.sourceId } shouldContainExactly
            listOf(
                "velodrom:disney-on-ice-praesentiert-mickys-magische-momente-velodrom-2027-03-13-1030",
                "velodrom:disney-on-ice-praesentiert-mickys-magische-momente-velodrom-2027-03-13-1430",
                "velodrom:disney-on-ice-praesentiert-mickys-magische-momente-velodrom-2027-03-13-1830"
            )
    }

    @Test
    fun `keeps both sittings of the arena's tattoo, whose permalinks already differ`() {
        // The venue spells this run's sessions into the URL itself; the collapse dropped them
        // anyway, because it keyed on date + title rather than on the id.
        val tattoo = scrape(VelomaxHall.MAX_SCHMELING_HALLE).filter { it.title == "Berlin Tattoo" }
        tattoo.map { it.eventDate to it.startTime } shouldContainExactly
            listOf(
                LocalDate.of(2026, 11, 7) to LocalTime.of(13, 30),
                LocalDate.of(2026, 11, 7) to LocalTime.of(19, 0),
                LocalDate.of(2026, 11, 8) to LocalTime.of(14, 30)
            )
    }

    @Test
    fun `flags a sold-out entry from its ticket signal`() {
        val soldOut = (scrape(VelomaxHall.VELODROM) + scrape(VelomaxHall.MAX_SCHMELING_HALLE)).filter { it.soldOut }
        soldOut.isNotEmpty() shouldBe true
    }

    @Test
    fun `resolves every entry to a real date`() {
        VelomaxHall.entries.forEach { hall ->
            scrape(hall).none { it.eventDate == LocalDate.MIN } shouldBe true
        }
    }

    @Test
    fun `returns no events for a page without a listing`() {
        scraper
            .scrape(Jsoup.parse("<html><body><main></main></body></html>", baseUrl), baseUrl, VelomaxHall.VELODROM)
            .shouldBeEmpty()
    }
}
