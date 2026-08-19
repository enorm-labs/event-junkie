package de.norm.events.scraper.loge

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Unit tests for [LogeWebsiteImporter].
 *
 * Focuses on the overview → detail merge: the detail page supplies the price and status while the
 * overview supplies the artist roster, and events fall back to overview data when a detail page
 * cannot be parsed.
 */
class LogeWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: LogeWebsiteImporter
    private val sourceUrl = "https://www.loge-berlin.org/event-list"
    private val estamoeUrl = "https://www.loge-berlin.org/event-details/estamoe-daloy-furie"
    private val kaOhUrl = "https://www.loge-berlin.org/event-details/ka-oh-zweikant"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/loge/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = LogeWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("loge-overview.html"), sourceUrl),
                etag = "\"loge-etag\"",
                lastModified = "Fri, 26 Jun 2026 09:48:03 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(estamoeUrl) } returns
            Jsoup.parse(fixture("loge-detail-estamoe.html"), estamoeUrl)
        coEvery { htmlFetcher.fetchDocument(kaOhUrl) } returns
            Jsoup.parse(fixture("loge-detail-cancelled.html"), kaOhUrl)

        // Every other detail page: return a JSON-LD-less document so the importer
        // degrades to overview data for that event.
        coEvery {
            htmlFetcher.fetchDocument(match { it != estamoeUrl && it != kaOhUrl })
        } returns Jsoup.parse(EMPTY_DETAIL, sourceUrl)
    }

    private fun events(result: ImportResult): List<ScrapedEvent> {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.LOGE
    }

    @Test
    fun `imports all events and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 9
            result.etag shouldBe "\"loge-etag\""
            result.lastModified shouldBe "Fri, 26 Jun 2026 09:48:03 GMT"
        }

    @Test
    fun `enriches an event with the detail-page price while keeping overview artists`() =
        runTest {
            val estamoe = events(importer.importEvents(sourceUrl)).first { it.sourceId == "loge:estamoe-daloy-furie" }
            estamoe.pricePresale shouldBe BigDecimal("12.30")
            estamoe.status shouldBe "SCHEDULED"
            estamoe.artists.map { it.role } shouldBe listOf("HEADLINER", "SUPPORT", "SUPPORT")
        }

    @Test
    fun `applies the cancelled status from the detail page`() =
        runTest {
            val kaOh = events(importer.importEvents(sourceUrl)).first { it.sourceId == "loge:ka-oh-zweikant" }
            kaOh.status shouldBe "CANCELLED"
            kaOh.pricePresale shouldBe null
            // Artists still come from the overview title "KA-OH + ZWEIKANT".
            kaOh.artists.map { it.name } shouldBe listOf("KA-OH", "ZWEIKANT")
        }

    @Test
    fun `falls back to overview data when the detail page has no JSON-LD`() =
        runTest {
            val moriBlau = events(importer.importEvents(sourceUrl)).first { it.sourceId == "loge:mori-blau-support" }
            moriBlau.eventDate shouldBe LocalDate.of(2026, 7, 18)
            moriBlau.pricePresale shouldBe null
            moriBlau.artists.map { it.name } shouldBe listOf("MORI BLAU")
        }

    @Test
    fun `returns NotModified when the listing has not changed`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for an empty listing page`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body></body></html>", sourceUrl),
                    etag = null,
                    lastModified = null
                )

            events(importer.importEvents(sourceUrl)).shouldBeEmpty()
        }

    private companion object {
        private const val EMPTY_DETAIL = "<html><body></body></html>"
    }
}
