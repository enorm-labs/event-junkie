package de.norm.events.scraper.renate

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The start time of a Renate row whose floor headings print none (#4818), on the homepage as it
 * read on 2026-11-10: Klubnacht rows state `Renate Klubnacht opens from 22:00.` beside a `Hot Tip`
 * about the early Green floor, and the `OUR HOUSE IS YOUR HOUSE` warm-up rows state
 * `Friday 18:00 – 22:00 / Saturday 16:00 – 22:00` or a running order.
 */
class RenateOverviewPageScraperStartTimeTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = RenateOverviewPageScraper(clock)
    private val baseUrl = "https://www.renate.cc/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        events = scraper.scrape(fixtureDocument("renate/renate-overview-2026-11-10.html", baseUrl), baseUrl)
    }

    private fun startOf(
        date: LocalDate,
        titlePrefix: String
    ): LocalTime? = events.first { it.eventDate == date && it.title.startsWith(titlePrefix) }.startTime

    private fun scrapeOne(html: String): ScrapedEvent = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

    @Test
    fun `starts a Klubnacht when its info block says it opens, not at the Hot Tip's early Green floor`() {
        // A Friday: the Hot Tip beside it reads `We open from 18:00 Fridays`.
        startOf(LocalDate.of(2026, 11, 13), "Renate Klubnacht") shouldBe LocalTime.of(22, 0)
        startOf(LocalDate.of(2026, 11, 14), "Renate Klubnacht") shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `starts an OUR HOUSE row at the opening hour its info block gives for that weekday`() {
        startOf(LocalDate.of(2026, 11, 13), "OUR HOUSE IS YOUR HOUSE: AFTERWORK") shouldBe LocalTime.of(18, 0)
        startOf(LocalDate.of(2026, 11, 20), "OUR HOUSE IS YOUR HOUSE: Vinyl Market") shouldBe LocalTime.of(18, 0)
        startOf(LocalDate.of(2026, 11, 21), "OUR HOUSE IS YOUR HOUSE w/ JOHN LOVELESS") shouldBe LocalTime.of(16, 0)
    }

    @Test
    fun `starts a running-order row at its first slot`() {
        // `16:00 - 19:00: Open Decks w/ Crush Collective`, then `19:00`, `20:30`, `21:15`.
        startOf(LocalDate.of(2026, 11, 14), "OUR HOUSE IS YOUR HOUSE: Open Decks") shouldBe LocalTime.of(16, 0)
        // The Klubnacht's own running order (`18:00 - 22:00: PROTECT THE DOLLS FUNDRAISER`) loses to its `opens from`.
        startOf(LocalDate.of(2026, 12, 5), "Renate Klubnacht") shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `stores a time for every night on the page but New Year's Eve, which prints none`() {
        events.filter { it.startTime == null }.map { it.title } shouldBe listOf("Renate NYE 2026/27")
    }

    @Test
    fun `never takes the Hot Tip's early opening as the Klubnacht's start`() {
        val html =
            """
            <div class="prog-row">
              <div class="prog-day">Fri.</div><div class="prog-date">13.11.</div>
              <div class="prog-title">Renate Klubnacht</div>
              <div class="prog-text">
                <p><strong>GREEN - Hosted by Carly Zeng</strong><br />Carly Zeng</p>
                <div class="info-text"><div>
                  <p><strong>Hot Tip: We open from 18:00 Fridays and 16:00 Saturdays.</strong></p>
                </div></div>
              </div>
            </div>
            """.trimIndent()
        scrapeOne(html).startTime.shouldBeNull()
    }

    @Test
    fun `keeps a floor heading's time ahead of the info block`() {
        val html =
            """
            <div class="prog-row">
              <div class="prog-day">Sat.</div><div class="prog-date">14.11.</div>
              <div class="prog-title">Renate Klubnacht</div>
              <div class="prog-text">
                <p><strong>GARDEN (from 14:00)</strong><br />Merlin Cum</p>
                <div class="info-text"><div><p>Renate Klubnacht opens from 22:00.</p></div></div>
              </div>
            </div>
            """.trimIndent()
        scrapeOne(html).startTime shouldBe LocalTime.of(14, 0)
    }

    @Test
    fun `ignores weekday hours that name another day`() {
        // New Year's Eve is a Thursday; the block names only Friday and Saturday.
        val html =
            """
            <div class="prog-row">
              <div class="prog-day">Thu.</div><div class="prog-date">31.12.</div>
              <div class="prog-title">Renate NYE</div>
              <div class="prog-text">
                <div class="info-text"><div><p>Friday 18:00 – 22:00 / Saturday 16:00 – 22:00</p></div></div>
              </div>
            </div>
            """.trimIndent()
        scrapeOne(html).startTime.shouldBeNull()
    }

    @Test
    fun `counts a running-order slot after midnight as later than the evening's`() {
        val html =
            """
            <div class="prog-row">
              <div class="prog-day">Sat.</div><div class="prog-date">14.11.</div>
              <div class="prog-title">Renate Late Night Open Decks with a title long enough to wrap across several lines on a phone</div>
              <div class="prog-text">
                <p>00:30 Afterhours b2b<br />21:15 Metrosoft (live)<br />23:00 - 00:30: Open Decks</p>
              </div>
            </div>
            """.trimIndent()
        scrapeOne(html).startTime shouldBe LocalTime.of(21, 15)
    }
}
