package de.norm.events.scraper.aeden

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AedenWebsiteImporter].
 *
 * Mocks [HtmlFetcher] so the whole multi-page fetch (entry page → four month pages) runs offline
 * against the saved fixtures.
 */
class AedenWebsiteImporterTest {
    private lateinit var importer: AedenWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private val entryUrl = "https://aedenberlin.com/events/"

    private fun monthUrl(month: String) = "https://aedenberlin.com/month/?month=$month"

    @BeforeEach
    fun setUp() {
        importer = AedenWebsiteImporter(htmlFetcher)
        val months = listOf("2026-07", "2026-08", "2026-09", "2026-10").map { monthUrl(it) to "aeden/aeden-month-$it.html" }
        stubFetcher(entryUrl to "aeden/aeden-overview.html", *months.toTypedArray(), fetcher = htmlFetcher)
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.AEDEN
    }

    @Test
    fun `importEvents follows the entry page to every month page and merges the results`() =
        runTest {
            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            // 10 July + 4 August + 1 September + 1 October nights.
            result.events shouldHaveSize 16
            result.events.all { it.eventType == EventType.PARTY.name } shouldBe true
            result.events.all { it.sourceId.startsWith(EventSource.AEDEN.sourceIdPrefix) } shouldBe true
            // Multi-page importer intentionally disables conditional caching.
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents deduplicates a night listed on two month pages`() =
        runTest {
            // The venue occasionally leaves a night on the neighbouring month page; the same date and
            // title yield the same sourceId, so it must be imported once.
            coEvery { htmlFetcher.fetchDocument(monthUrl("2026-09")) } returns
                fixtureDocument("aeden/aeden-month-2026-08.html", monthUrl("2026-09"))

            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            // The 4 August nights are now listed twice but collapse to one each: 10 + 4 + 1 = 15.
            result.events shouldHaveSize 15
            result.events.map { it.sourceId }.distinct() shouldHaveSize 15
        }

    @Test
    fun `importEvents returns an empty success when the entry page links no month pages`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns
                Jsoup.parse("<html><body><p>No programme yet</p></body></html>", entryUrl)

            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `importEvents keeps the other months when one fails, and reports the run incomplete`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(monthUrl("2026-08")) } throws HttpFetchException(503, monthUrl("2026-08"))

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            // 10 July + 1 September + 1 October nights.
            result.events shouldHaveSize 12
            result.complete shouldBe false
        }
}
