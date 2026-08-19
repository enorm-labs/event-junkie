package de.norm.events.scraper.urania

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
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

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/urania/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = UraniaWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture("urania-kalender.html"), sourceUrl),
                etag = "\"urania-etag\"",
                lastModified = "Mon, 03 Aug 2026 09:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        listOf(
            "demokratie-vor-der-wahl" to "demokratie-vor-der-wahl",
            "how-context-modulates-memory-in-flies-and-humans" to "how-context-modulates-memory",
            "die-pionierinnen-der-goldenen-1920er-jahre" to "die-pionierinnen"
        ).forEach { (slug, fixture) ->
            coEvery { htmlFetcher.fetchDocument(eventUrl(slug)) } returns
                Jsoup.parse(readFixture("urania-event-$fixture.html"), eventUrl(slug))
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

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.URANIA
    }
}
