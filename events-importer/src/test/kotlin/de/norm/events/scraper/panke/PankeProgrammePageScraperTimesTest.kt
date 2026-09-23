package de.norm.events.scraper.panke

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * Time-parsing tests for [PankeProgrammePageScraper] against the 2026-09-23 snapshot of the
 * upcoming list (#1758).
 *
 * The other fixture is a whole page from August and states every clock the one way the scraper
 * used to handle. This one is the five upcoming articles of a later capture, kept because between
 * them they write the clock in all three forms the venue uses — `19:00`, `18:00:00` and the bare
 * `19.` — and one of them prints the precise `Doors … · Concert …` pair in its body.
 */
class PankeProgrammePageScraperTimesTest {
    private val scraper = PankeProgrammePageScraper()
    private val sourceUrl = "https://www.pankeculture.com/programme/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/panke/panke-programme-times.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `takes both clocks from the body's pair, the prose line stating only the doors`() {
        // "starting at 19." beside "🕐 Doors 19:00 · Concert 21:00" — the prose repeats the doors,
        // and reading it as the start put a door time on the card for two hours (#1758).
        val fluxo = event("panke:17458")
        fluxo.doorsTime shouldBe LocalTime.of(19, 0)
        fluxo.startTime shouldBe LocalTime.of(21, 0)
    }

    @Test
    fun `reads a bare hour, the minutes being the venue's to omit`() {
        // The same article is the only one that writes no minutes, so this is what the pair rescued.
        scraper
            .scrape(Jsoup.parse(articleStating("19."), sourceUrl), sourceUrl)
            .single()
            .startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `keeps the prose clock as the start where the body prints no pair`() {
        // Four of the five state one time and say nothing about doors; none may gain one.
        event("panke:17444").startTime shouldBe LocalTime.of(18, 0) // "starting at 18:00:00."
        event("panke:17428").startTime shouldBe LocalTime.of(23, 0)
        event("panke:17460").startTime shouldBe LocalTime.of(19, 0)
        event("panke:17465").startTime shouldBe LocalTime.of(20, 0)
        events.filter { it.sourceId != "panke:17458" }.forEach { it.doorsTime.shouldBeNull() }
    }

    @Test
    fun `states a clock for every upcoming event`() {
        events shouldHaveSize 5
        events.none { it.startTime == null } shouldBe true
    }

    @Test
    fun `needs both labels before it reads a pair`() {
        // A body naming one clock is not a pair, and its prose line stays the start.
        val doorsOnly = articleStating("20:00.", body = "<p>Doors 19:00</p>")
        val parsed = scraper.scrape(Jsoup.parse(doorsOnly, sourceUrl), sourceUrl).single()
        parsed.startTime shouldBe LocalTime.of(20, 0)
        parsed.doorsTime.shouldBeNull()
    }

    /** One article of the venue's template, stating [clock] on its prose line and [body] as its text. */
    private fun articleStating(
        clock: String,
        body: String = "<p>Nothing else.</p>"
    ): String =
        """
        <div class="et_pb_events_0">
          <article id="1">
            <div class="event-content-wrapper" data-date="2026-09-23">
              <h2 class="entry-title">A night</h2>
              <div class="post-content-full">
                <div class="et_pb_column et_pb_column_1_4"><p class="eventInfo"><span><small>The event takes place on the &nbsp;</small>23rd of September<small> starting at &nbsp;</small>$clock</span></p></div>
                <div class="et_pb_column et_pb_column_3_4">$body</div>
              </div>
            </div>
          </article>
        </div>
        """.trimIndent()
}
