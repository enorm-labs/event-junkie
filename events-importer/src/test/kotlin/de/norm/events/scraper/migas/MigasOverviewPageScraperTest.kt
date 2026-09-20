package de.norm.events.scraper.migas

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MigasOverviewPageScraper], against a real snapshot of `/program/`.
 *
 * No clock is pinned: unlike the retro listings, migas states a full ISO year in the calendar
 * button's `data-start-date`, so nothing here infers a year. The remaining tests guard the two
 * things the page shape makes easy to get wrong — reading the lazy-loaded `data-src` rather than the
 * placeholder `src`, and keeping an album-playback title out of the artist table.
 *
 * The snapshot is the live page with its `<script>` elements removed — 44% of the bytes, none of
 * them inside `.events-list`, so the parsed markup is byte-identical. They are dropped because the
 * page inlines WordPress core's `wp-emoji-loader`, whose DOM handling CodeQL flags as a
 * high-severity XSS finding on every scan of the fixture. **Do not re-capture the page to "restore"
 * them.**
 */
class MigasOverviewPageScraperTest {
    private val scraper = MigasOverviewPageScraper()
    private val baseUrl = "https://migas.berlin/program/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        events = scraper.scrape(Jsoup.parse(fixture(), baseUrl))
    }

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/migas/migas-overview.html")!!
            .bufferedReader()
            .readText()

    private fun event(title: String): ScrapedEvent = events.first { it.title == title }

    @Test
    fun `scrape extracts every event on the page`() {
        events shouldHaveSize 10
    }

    @Test
    fun `scrape maps a guest listening session with all its fields`() {
        val session = event("SITAAD")

        session.eventDate shouldBe LocalDate.of(2026, 8, 5)
        session.startTime shouldBe LocalTime.of(20, 0)
        session.eventType shouldBe EventType.OTHER.name
        session.sourceUrl shouldBe "https://migas.berlin/event/sitaad/"
        session.sourceId shouldBe "migas:sitaad"
        session.imageUrl shouldBe
            "https://migas.berlin/wp-content/uploads/2026/07/5.08-live-listening-session-SITAAD-collab-@sitaadstudio-550x395.jpeg"
        session.description!! shouldContain "Somali funk, Ethio-jazz, Sudanese pop"
        session.artists shouldContainExactly listOf(ScrapedArtist(name = "SITAAD", role = "DJ", titleDerived = true))
    }

    @Test
    fun `scrape types a playing night as a club night and bills its selector as a DJ`() {
        val night = event("vip client")

        night.eventType shouldBe EventType.CLUB_NIGHT.name
        night.eventDate shouldBe LocalDate.of(2026, 8, 6)
        night.sourceId shouldBe "migas:vip-client"
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "vip client", role = "DJ", titleDerived = true))
    }

    @Test
    fun `scrape splits a co-billed selector pair into two DJs`() {
        event("eric.a & llupe").artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "eric.a", role = "DJ", titleDerived = true),
                ScrapedArtist(name = "llupe", role = "DJ", titleDerived = true)
            )
    }

    @Test
    fun `scrape mints no artist for an album playback night`() {
        // The title names the record being played, not an act appearing at the venue.
        val playback = events.first { it.title.startsWith("Kyuss") }

        playback.title shouldBe "Kyuss – Blues for the Red Sun (Elektra/Asylum Records, 1992 • 52 min • vinyl)"
        playback.eventType shouldBe EventType.OTHER.name
        playback.artists.shouldBeEmpty()
    }

    @Test
    fun `scrape mints no artist for a various-artists compilation playback`() {
        events.first { it.title.startsWith("Various") }.artists.shouldBeEmpty()
    }

    @Test
    fun `scrape reads the lazy-loaded image rather than the inline svg placeholder`() {
        // Every img's src is a data URI until the theme's lazy-loader swaps in data-src.
        events.forEach { it.imageUrl!! shouldStartWith "https://migas.berlin/wp-content/uploads/" }
    }

    @Test
    fun `scrape leaves the fields migas never publishes empty`() {
        val night = event("Zahra")

        night.genre.shouldBeNull()
        night.doorsTime.shouldBeNull()
        night.ticketUrl.shouldBeNull()
        night.pricePresale.shouldBeNull()
        night.priceBoxOffice.shouldBeNull()
        night.priceNote.shouldBeNull()
        night.subtitle.shouldBeNull()
        night.soldOut shouldBe false
        night.free shouldBe false
    }

    @Test
    fun `scrape orders events as the page lists them, all dated and titled`() {
        events.map { it.eventDate } shouldContainExactly
            listOf(5, 6, 7, 12, 14, 15, 19, 21, 22, 26).map { LocalDate.of(2026, 8, it) }
        events.forEach { it.title.isNotBlank() shouldBe true }
    }

    @Test
    fun `scrape prefixes every sourceId with the migas source and the permalink slug`() {
        events.forEach {
            it.sourceId shouldStartWith "migas:"
            it.sourceId.removePrefix("migas:").isNotBlank() shouldBe true
            it.sourceUrl shouldStartWith "https://migas.berlin/event/"
        }
    }

    @Test
    fun `scrape returns nothing for a page with no events list`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", baseUrl)).shouldBeEmpty()
    }

    @Test
    fun `scrape skips an event whose popup is missing`() {
        val html =
            """
            <div class="events-list">
              <a href="#" data-target="#popup-1" class="event-item floating-media">
                <h5 class="event-item-title"><span>Ghost</span></h5>
              </a>
            </div>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, baseUrl)).shouldBeEmpty()
    }

    @Test
    fun `scrape skips an event with no share permalink so sourceIds stay stable`() {
        // Re-keying onto a second identity scheme would duplicate the whole programme on upsert.
        scraper.scrape(Jsoup.parse(popupFixture(shareButton = ""), baseUrl)).shouldBeEmpty()
    }

    @Test
    fun `scrape skips an event with an unparseable start date`() {
        scraper.scrape(Jsoup.parse(popupFixture(startDate = "tba"), baseUrl)).shouldBeEmpty()
    }

    @Test
    fun `scrape falls back to the shared iso helpers for an offset-less start date`() {
        val parsed = scraper.scrape(Jsoup.parse(popupFixture(startDate = "2026-09-01T21:30"), baseUrl))

        parsed shouldHaveSize 1
        parsed.first().eventDate shouldBe LocalDate.of(2026, 9, 1)
        parsed.first().startTime shouldBe LocalTime.of(21, 30)
    }

    @Test
    fun `scrape keeps a date-only start date and leaves the time unset`() {
        val parsed = scraper.scrape(Jsoup.parse(popupFixture(startDate = "2026-09-01"), baseUrl))

        parsed shouldHaveSize 1
        parsed.first().eventDate shouldBe LocalDate.of(2026, 9, 1)
        parsed.first().startTime.shouldBeNull()
    }

    /** A minimal anchor + modal pair mirroring the theme's markup, with the varying bits injected. */
    private fun popupFixture(
        startDate: String = "2026-09-01T20:00:00+02:00",
        shareButton: String = """<button data-target="share" data-url="https://migas.berlin/event/probe/"></button>"""
    ): String =
        """
        <div class="events-list">
          <a href="#" data-target="#popup-1" class="event-item floating-media">
            <h5 class="event-item-title"><span>Probe</span></h5>
            <div class="event-item-category"><span>playing</span></div>
          </a>
          <div id="popup-1" class="event-popup">
            <div class="event-popup-body">
              <div class="event-popup-content">
                <ul class="nav nav-inline">
                  <li><button data-target="add-to-calendar" data-start-date="$startDate"></button></li>
                  <li>$shareButton</li>
                </ul>
              </div>
            </div>
          </div>
        </div>
        """.trimIndent()
}
