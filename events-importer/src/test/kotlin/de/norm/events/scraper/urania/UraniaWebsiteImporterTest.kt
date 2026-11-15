package de.norm.events.scraper.urania

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [UraniaWebsiteImporter].
 *
 * Only three event pages are stubbed; every other detail fetch throws, exercising the base class's
 * degrade-to-calendar fallback.
 */
class UraniaWebsiteImporterTest {
    private lateinit var importer: UraniaWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.urania.de/kalender/"

    private fun eventUrl(slug: String) = "https://www.urania.de/event/$slug/"

    @BeforeEach
    fun setUp() {
        importer = UraniaWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = fixtureDocument("urania/urania-kalender.html", sourceUrl),
                etag = "\"urania-etag\"",
                lastModified = "Mon, 03 Aug 2026 09:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        listOf(
            "demokratie-vor-der-wahl" to "demokratie-vor-der-wahl",
            "how-context-modulates-memory-in-flies-and-humans" to "how-context-modulates-memory",
            "die-pionierinnen-der-goldenen-1920er-jahre" to "die-pionierinnen"
        ).forEach { (slug, fixture) ->
            stubFetcher(eventUrl(slug) to "urania/urania-event-$fixture.html", fetcher = htmlFetcher)
        }
    }

    @Test
    fun `importEvents returns the whole calendar`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 17
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"urania-etag\""
            result.lastModified shouldBe "Mon, 03 Aug 2026 09:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns empty list for a page without a calendar`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><div class='c-event-calendar'></div></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `merges the event page onto the calendar's date`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val opening = result.events.first { it.sourceId == "urania:demokratie-vor-der-wahl" }

            // The calendar owns the date and clock; it states them machine-readably.
            opening.eventDate shouldBe LocalDate.of(2026, 9, 3)
            opening.startTime shouldBe LocalTime.of(19, 30)
            // Event-page only.
            opening.imageUrl!! shouldStartWith "https://www.urania.de/wp-content/uploads/"
            opening.description!!.isNotBlank() shouldBe true
            opening.pricePresale shouldBe BigDecimal("8")
            opening.eventType shouldBe EventType.READING.name
        }

    @Test
    fun `keeps a free evening free through the merge`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val memory = result.events.first { it.sourceId == "urania:how-context-modulates-memory-in-flies-and-humans" }
            memory.free shouldBe true
            memory.pricePresale.shouldBeNull()
        }

    @Test
    fun `falls back to the calendar when an event page cannot be fetched`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val leibspeisen = result.events.first { it.sourceId == "urania:leibspeisen" }

            leibspeisen.title shouldBe "Leibspeisen"
            leibspeisen.eventDate shouldBe LocalDate.of(2026, 9, 4)
            leibspeisen.startTime shouldBe LocalTime.of(19, 30)
            leibspeisen.subtitle shouldBe "LANGE LINIEN · Podiumsgespräch"
            leibspeisen.artists.map { it.name } shouldBe listOf("Eva Gritzmann", "Denis Scheck")
            leibspeisen.ticketUrl!! shouldStartWith "https://uraniaberlin.reservix.de/"
            // Event-page-only fields stay empty rather than aborting the import.
            leibspeisen.imageUrl.shouldBeNull()
            leibspeisen.description.shouldBeNull()
            leibspeisen.pricePresale.shouldBeNull()
        }

    // The calendar and the event page both bill the host; the merge keeps the page's three speakers, and the calendar's when the page fails.
    @Test
    fun `bills the host named after bei through the merge and the fallback`() =
        runTest {
            val slug = "schoenheitssalon-wie-du-wieder-aussiehst"
            val billing = "Jens Balzer und Victoria Reichelt bei Rabea Weihser"
            val calendar =
                """
                <div class="c-event-calendar_day js-day" data-day="11-mi-11-2026">
                  <div class="c-event-calendar-item js-calendar-event-item">
                    <div class="c-event-calendar-item_time"><span>19:30 Uhr</span></div>
                    <a class="c-event-calendar-item_content" href="${eventUrl(slug)}">
                      <h3 class="o-h3">Wie du wieder aussiehst! Hässlichkeit und Subversion</h3>
                      <h6 class="o-h6">Schönheitssalon</h6>
                      <div class="c-event-calendar-item_content_text"><p><strong>$billing</strong></p></div>
                    </a>
                  </div>
                </div>
                """.trimIndent()
            val page =
                """
                <h6 class="c-event-article_content_format o-h6">Schönheitssalon</h6>
                <h1 class="c-event-article_content_title o-h1">Wie du wieder aussiehst! Hässlichkeit und Subversion</h1>
                <p class="c-event-article_content_artist"><strong>$billing</strong></p>
                <h4 class="c-event-article_content_date-time"><strong>Mi, 11.11.2026</strong> | 19:30 Uhr</h4>
                """.trimIndent()
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse(calendar, sourceUrl), etag = null, lastModified = null)
            val speakers = listOf("Jens Balzer", "Victoria Reichelt", "Rabea Weihser")

            val fallback = importer.importEvents(sourceUrl)
            fallback.shouldBeInstanceOf<ImportResult.Success>()
            fallback.events
                .single()
                .artists
                .map { it.name } shouldBe speakers

            coEvery { htmlFetcher.fetchDocument(eventUrl(slug)) } returns Jsoup.parse(page, eventUrl(slug))
            val merged = importer.importEvents(sourceUrl)
            merged.shouldBeInstanceOf<ImportResult.Success>()
            merged.events
                .single()
                .artists
                .map { it.name } shouldBe speakers
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.URANIA
    }
}
