package de.norm.events.scraper.goldengate

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [GoldenGateWebsiteImporter].
 *
 * Golden Gate is a single-page source, so the importer's job is limited to the fetch → scrape →
 * `ImportResult` path: no detail pages are requested.
 */
class GoldenGateWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = GoldenGateWebsiteImporter(htmlFetcher)
    private val overviewUrl = "https://goldengate-berlin.de/"

    private fun stubOverview() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/goldengate/goldengate-overview.html")!!
                .bufferedReader()
                .readText()
        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(html, overviewUrl),
                etag = "\"goldengate-etag\"",
                lastModified = "Sat, 01 Aug 2026 03:00:00 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.GOLDEN_GATE
    }

    @Test
    fun `returns NotModified when the homepage is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(overviewUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports the announced block and propagates conditional headers`() =
        runTest {
            stubOverview()
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // All three announced nights are returned; past-dated ones are dropped downstream by
            // EventUpsertService, not here.
            result.events shouldHaveSize 3
            result.events.map { it.eventDate }.first() shouldBe LocalDate.of(2026, 7, 30)
            result.etag shouldBe "\"goldengate-etag\""
            result.lastModified shouldBe "Sat, 01 Aug 2026 03:00:00 GMT"
        }

    @Test
    fun `returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body><main></main></body></html>", overviewUrl),
                    etag = null,
                    lastModified = null
                )
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
