package de.norm.events.scraper.migas

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureText
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MigasWebsiteImporter].
 *
 * The conditional-request tests are the point of this class: migas' `Last-Modified` tracks page
 * edits while the listing itself rolls forward daily, so replaying it would freeze the programme.
 */
class MigasWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: MigasWebsiteImporter
    private val sourceUrl = "https://migas.berlin/program/"

    @BeforeEach
    fun setUp() {
        importer = MigasWebsiteImporter(htmlFetcher)
        stubFetcher(sourceUrl to "migas/migas-overview.html", fetcher = htmlFetcher)
        coEvery { htmlFetcher.postForm(ajaxUrl, page(2)) } returns fixtureText("migas/migas-load-events-page-2.html")
    }

    private val ajaxUrl = "https://migas.berlin/wp-admin/admin-ajax.php"

    private fun page(n: Int) = mapOf("action" to "load_events", "paged" to "$n", "type" to "upcoming")

    @Test
    fun `eventSource identifies this importer as migas`() {
        importer.eventSource shouldBe EventSource.MIGAS
    }

    @Test
    fun `importEvents reads the page and the Load More page the button states, one POST each`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 16
            result.events.map { it.sourceId }.distinct() shouldHaveSize 16
            coVerify(exactly = 1) { htmlFetcher.postForm(ajaxUrl, page(2)) }
            coVerify(exactly = 0) { htmlFetcher.postForm(ajaxUrl, page(3)) }
        }

    @Test
    fun `a later page that fails keeps the first page's events and reports the walk incomplete`() =
        runTest {
            coEvery { htmlFetcher.postForm(ajaxUrl, page(2)) } throws HttpFetchException(503, ajaxUrl)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 10
            // The unread page's events must survive the stale cleanup.
            result.complete shouldBe false
        }

    @Test
    fun `a page with no Load More button asks for no further page`() =
        runTest {
            val html = fixtureText("migas/migas-overview.html").replace("data-target=\"load-more\"", "data-target=\"gone\"")
            coEvery { htmlFetcher.fetchDocument(sourceUrl) } returns Jsoup.parse(html, sourceUrl)

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>().events shouldHaveSize 10
            coVerify(exactly = 0) { htmlFetcher.postForm(any(), any()) }
        }

    @Test
    fun `importEvents never returns cache headers so the next run re-fetches`() =
        runTest {
            val result = importer.importEvents(sourceUrl, etag = "\"abc123\"", lastModified = "Sun, 02 Aug 2026 22:43:21 GMT")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents ignores stored conditional headers rather than sending them`() =
        runTest {
            // A 304 would freeze the rolling "upcoming" horizon — see the importer KDoc.
            importer.importEvents(sourceUrl, etag = "\"abc123\"", lastModified = "Sun, 02 Aug 2026 22:43:21 GMT")

            coVerify(exactly = 1) { htmlFetcher.fetchDocument(sourceUrl) }
            coVerify(exactly = 0) { htmlFetcher.fetch(any(), any(), any()) }
        }

    @Test
    fun `importEvents returns an empty success for a page with no programme`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(sourceUrl) } returns Jsoup.parse("<html><body></body></html>", sourceUrl)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
