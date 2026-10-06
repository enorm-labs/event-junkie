package de.norm.events.scraper.cassiopeia

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit tests for [CassiopeiaOverviewPageScraper].
 *
 * Focuses on the deduplication logic that prefers Webflow CMS canonical
 * URLs (with numeric ID suffix) over legacy plain-text slugs. The clock is pinned to
 * 2026-05-01 — before every synthetic fixture date — so events count as upcoming and
 * survive the past-event cutoff, which is exercised in its own test.
 */
class CassiopeiaOverviewPageScraperTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC)
    private val scraper = CassiopeiaOverviewPageScraper(clock)
    private val sourceUrl = "https://cassiopeia-berlin.de/club"

    @Nested
    inner class Deduplication {
        @Test
        fun `prefers entry with CMS numeric ID when duplicates exist`() {
            val html =
                buildOverviewPage(
                    eventItem(title = "Döll", date = "15", month = "05", year = "Mai 2026", slug = "doll"),
                    eventItem(title = "Döll", date = "15", month = "05", year = "Mai 2026", slug = "doell-111601080")
                )
            val document = Jsoup.parse(html, sourceUrl)

            val events = scraper.scrape(document, sourceUrl)

            events shouldHaveSize 1
            events.first().sourceId shouldBe "cassiopeia:doell-111601080"
        }

        @Test
        fun `prefers CMS numeric ID regardless of list ordering`() {
            // CMS canonical entry appears first, legacy second
            val html =
                buildOverviewPage(
                    eventItem(title = "Döll", date = "15", month = "05", year = "Mai 2026", slug = "doell-111601080"),
                    eventItem(title = "Döll", date = "15", month = "05", year = "Mai 2026", slug = "doll")
                )
            val document = Jsoup.parse(html, sourceUrl)

            val events = scraper.scrape(document, sourceUrl)

            events shouldHaveSize 1
            events.first().sourceId shouldBe "cassiopeia:doell-111601080"
        }

        @Test
        fun `keeps first entry when no duplicate has CMS numeric ID`() {
            // Both entries have plain slugs — neither has a CMS numeric ID
            val html =
                buildOverviewPage(
                    eventItem(title = "Open Decks", date = "20", month = "06", year = "Juni 2026", slug = "open-decks"),
                    eventItem(title = "Open Decks", date = "20", month = "06", year = "Juni 2026", slug = "open-decks-session")
                )
            val document = Jsoup.parse(html, sourceUrl)

            val events = scraper.scrape(document, sourceUrl)

            events shouldHaveSize 1
            events.first().sourceId shouldBe "cassiopeia:open-decks"
        }

        @Test
        fun `does not deduplicate events with same title but different dates`() {
            val html =
                buildOverviewPage(
                    eventItem(title = "Open Decks", date = "20", month = "06", year = "Juni 2026", slug = "open-decks-1"),
                    eventItem(title = "Open Decks", date = "27", month = "06", year = "Juni 2026", slug = "open-decks-2")
                )
            val document = Jsoup.parse(html, sourceUrl)

            val events = scraper.scrape(document, sourceUrl)

            events shouldHaveSize 2
        }

        @Test
        fun `deduplication is case-insensitive on title`() {
            val html =
                buildOverviewPage(
                    eventItem(title = "DÖLL", date = "15", month = "05", year = "Mai 2026", slug = "doll"),
                    eventItem(title = "döll", date = "15", month = "05", year = "Mai 2026", slug = "doell-111601080")
                )
            val document = Jsoup.parse(html, sourceUrl)

            val events = scraper.scrape(document, sourceUrl)

            events shouldHaveSize 1
            events.first().sourceId shouldBe "cassiopeia:doell-111601080"
        }

        @Test
        fun `no deduplication needed when all events are unique`() {
            val html =
                buildOverviewPage(
                    eventItem(title = "Event A", date = "10", month = "05", year = "Mai 2026", slug = "event-a-111111111"),
                    eventItem(title = "Event B", date = "11", month = "05", year = "Mai 2026", slug = "event-b-222222222")
                )
            val document = Jsoup.parse(html, sourceUrl)

            val events = scraper.scrape(document, sourceUrl)

            events shouldHaveSize 2
        }
    }

    @Test
    fun `drops events dated before today, keeping same-day and later ones`() {
        val html =
            buildOverviewPage(
                eventItem(title = "Past Show", date = "15", month = "05", year = "Mai 2026", slug = "past-111111111"),
                eventItem(title = "Future Show", date = "20", month = "06", year = "Juni 2026", slug = "future-222222222")
            )
        // Today is 2026-06-01: the 15 May show is past and must be dropped, leaving 20 June.
        val scraperInJune =
            CassiopeiaOverviewPageScraper(Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC))

        val events = scraperInJune.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)

        events shouldHaveSize 1
        events.first().title shouldBe "Future Show"
        events.first().eventDate shouldBe LocalDate.of(2026, 6, 20)
    }

    @Test
    fun `types a Sonstiges quiz night by its title`() {
        // The listing is the fallback for a failed detail fetch, so it types the same way (#2791).
        val html =
            buildOverviewPage(
                eventItem(
                    title = "Quizcarraldo Kneipenquiz",
                    date = "07",
                    month = "10",
                    year = "Oktober 2026",
                    slug = "quizcarraldo-kneipenquiz-111687594",
                    category = "Sonstiges",
                    genre = "Kneipenquiz"
                )
            )

        val event = scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl).single()

        event.eventType shouldBe "QUIZ"
        event.artists shouldBe emptyList()
    }

    // -- HTML fixture builders ---

    /**
     * Builds a minimal Cassiopeia overview page HTML with the given event items.
     * Only includes the structural elements required by the scraper.
     */
    private fun buildOverviewPage(vararg items: String): String =
        """
        <!DOCTYPE html>
        <html lang="de-DE">
        <head><title>Cassiopeia Berlin – Club</title></head>
        <body>
        <div class="content-wrapper">
            <div class="event-list-wrapper w-dyn-list">
                <div class="event-list w-dyn-items" role="list">
                    ${items.joinToString("\n")}
                </div>
            </div>
        </div>
        </body>
        </html>
        """.trimIndent()

    /**
     * Creates a single `.event-item` HTML block matching the Webflow CMS structure.
     * Includes only the minimal elements the scraper needs for parsing and deduplication.
     */
    @Suppress("LongParameterList") // Test fixture builder — clarity over parameter count
    private fun eventItem(
        title: String,
        date: String,
        month: String,
        year: String,
        slug: String,
        category: String = "Konzert",
        genre: String = "Rock"
    ): String =
        """
        <div class="event-item w-dyn-item" role="listitem">
            <a class="event-wrapper w-inline-block" href="/event/$slug">
                <div class="event-content-wrapper">
                    <div class="event-image-wrapper" style="background-image:url(&quot;https://cdn.example.com/$slug.jpg&quot;)">
                    </div>
                    <div class="event-text-wrapper">
                        <div class="event-date-wrapper">
                            <h2 class="event-date">$date</h2>
                            <h2 class="event-date">.</h2>
                            <h2 class="event-date">$month</h2>
                            <h2 class="event-date">.</h2>
                            <h2 class="event-date faker" fs-cmsfilter-field="date">$year</h2>
                        </div>
                        <div class="event-title-wrapper">
                            <h2 class="subheading event" fs-cmsfilter-field="title">$title</h2>
                        </div>
                        <div class="event-details-overwrapper mobile">
                            <div class="event-detail-wrapper">
                                <div class="event-detail-subwrapper">
                                    <div class="event-details _4">Einlass</div>
                                    <div class="event-details _5">19:00</div>
                                    <div class="event-details _7">Beginn</div>
                                    <div class="event-details _8">20:00</div>
                                </div>
                                <div class="event-detail-subwrapper">
                                    <div class="event-details _9" fs-cmsfilter-field="category">$category</div>
                                    <div class="event-details _11" fs-cmsfilter-field="genre">$genre</div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
                <div class="flag-wrapper">
                    <div class="event-detail sold-out w-condition-invisible">
                        <div class="event-text dark">Cancelled</div>
                    </div>
                    <div class="event-detail sold-out w-condition-invisible">
                        <div class="event-text dark">Sold-Out</div>
                    </div>
                </div>
            </a>
        </div>
        """.trimIndent()
}
