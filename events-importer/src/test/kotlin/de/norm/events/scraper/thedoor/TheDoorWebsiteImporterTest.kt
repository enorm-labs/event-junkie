package de.norm.events.scraper.thedoor

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for [TheDoorWebsiteImporter]. */
class TheDoorWebsiteImporterTest {
    private lateinit var importer: TheDoorWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://thedoor.club/events/"

    private fun fixture() =
        javaClass.classLoader
            .getResourceAsStream("scraper/thedoor/thedoor-events.html")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = TheDoorWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture(), sourceUrl),
                etag = "\"door-1\"",
                lastModified = "Sat, 17 Oct 2026 09:00:00 GMT"
            )
    }

    @Test
    fun `importEvents reads the month's grid from one page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 9
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"door-1\""
            result.lastModified shouldBe "Sat, 17 Oct 2026 09:00:00 GMT"
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
    fun `the cleanup stays within the scraped month and nothing past the page is fetched`() {
        importer.listsWholeProgramme shouldBe false
        importer.fetchesBeyondEntryPage shouldBe false
    }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.THE_DOOR
    }
}
