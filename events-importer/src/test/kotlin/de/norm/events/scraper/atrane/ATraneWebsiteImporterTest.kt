package de.norm.events.scraper.atrane

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

/** Unit tests for [ATraneWebsiteImporter]: one conditional fetch of the programme page. */
class ATraneWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = ATraneWebsiteImporter(htmlFetcher)
    private val programmeUrl = "https://a-trane.de/programm/"

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/atrane/atrane-programm.html")!!
            .bufferedReader()
            .readText()

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.A_TRANE
    }

    @Test
    fun `imports every concert and propagates conditional headers`() =
        runTest {
            coEvery { htmlFetcher.fetch(programmeUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse(fixture(), programmeUrl),
                    etag = "\"atrane-etag\"",
                    lastModified = "Thu, 01 Oct 2026 08:00:00 GMT"
                )

            val result = importer.importEvents(programmeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 55
            result.etag shouldBe "\"atrane-etag\""
            result.lastModified shouldBe "Thu, 01 Oct 2026 08:00:00 GMT"
        }

    @Test
    fun `returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(programmeUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(programmeUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(programmeUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", programmeUrl), etag = null, lastModified = null)

            val result = importer.importEvents(programmeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
