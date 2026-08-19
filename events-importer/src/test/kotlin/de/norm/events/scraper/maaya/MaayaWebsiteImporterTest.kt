package de.norm.events.scraper.maaya

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MaayaWebsiteImporter].
 */
class MaayaWebsiteImporterTest {
    private lateinit var importer: MaayaWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://maaya.de/"

    private fun readFixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/maaya/maaya-overview.html")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = MaayaWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture(), sourceUrl),
                etag = "\"maaya-etag\"",
                lastModified = "Tue, 04 Aug 2026 17:24:56 GMT"
            )
    }

    @Test
    fun `importEvents returns the venue's NEXT DATES programme`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 15
        }

    @Test
    fun `importEvents needs a single request, there being no detail pages`() =
        runTest {
            importer.importEvents(sourceUrl)
            coVerify(exactly = 1) { htmlFetcher.fetch(sourceUrl, any(), any()) }
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"maaya-etag\""
            result.lastModified shouldBe "Tue, 04 Aug 2026 17:24:56 GMT"
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns empty list for a page without a programme`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><main></main></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `importEvents maps the events it collected`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val poolParty = result.events.first { it.sourceId == "maaya:2026-08-09-maaya-x-fade-pool-party" }

            poolParty.title shouldBe "MAAYA X FADE POOL PARTY"
            poolParty.eventDate shouldBe LocalDate.of(2026, 8, 9)
            poolParty.startTime shouldBe LocalTime.of(12, 0)
            poolParty.eventType shouldBe EventType.PARTY.name
            poolParty.ticketUrl shouldBe "https://xceed.me/en/berlin/event/maaya-x-fade-pool-party/239260/channel/maaya-berlin"
            poolParty.sourceUrl shouldBe sourceUrl
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.MAAYA
    }
}
