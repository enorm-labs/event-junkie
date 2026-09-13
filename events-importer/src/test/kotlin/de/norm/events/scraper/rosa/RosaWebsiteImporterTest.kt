package de.norm.events.scraper.rosa

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
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for [RosaWebsiteImporter]. */
class RosaWebsiteImporterTest {
    private lateinit var importer: RosaWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.rosaclub.de/dates"

    private fun fixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("scraper/rosa/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = RosaWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("rosa-overview.html"), sourceUrl),
                etag = "\"abc123\"",
                lastModified = "Fri, 11 Sep 2026 10:00:00 GMT"
            )
    }

    @Test
    fun `importEvents reads the programme behind the age gate`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 2
        }

    @Test
    fun `importEvents sends the age-gate cookie, without which the page carries no programme`() =
        runTest {
            val cookies = slot<Map<String, String>>()
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), capture(cookies)) } returns
                FetchResult.Success(
                    document = Jsoup.parse(fixture("rosa-overview.html"), sourceUrl),
                    etag = null,
                    lastModified = null
                )

            importer.importEvents(sourceUrl)

            cookies.captured shouldBe mapOf("rosa_age_ok" to "1")
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"abc123\""
            result.lastModified shouldBe "Fri, 11 Sep 2026 10:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `importEvents returns no events for an empty page`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body></body></html>", sourceUrl),
                    etag = null,
                    lastModified = null
                )

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.ROSA
    }
}
