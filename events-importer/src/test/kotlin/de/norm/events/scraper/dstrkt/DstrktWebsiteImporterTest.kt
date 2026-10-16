package de.norm.events.scraper.dstrkt

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

/** Unit tests for [DstrktWebsiteImporter]. */
class DstrktWebsiteImporterTest {
    private lateinit var importer: DstrktWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.dstrkt.de/"

    private fun fixture() =
        javaClass.classLoader
            .getResourceAsStream("scraper/dstrkt/dstrkt-overview.html")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = DstrktWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture(), sourceUrl),
                etag = "\"wix-dstrkt\"",
                lastModified = "Sat, 17 Oct 2026 09:00:00 GMT"
            )
    }

    @Test
    fun `importEvents reads every night from the home page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 3
            result.complete shouldBe true
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"wix-dstrkt\""
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
    fun `the listing is the whole programme and nothing past it is fetched`() {
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe false
    }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.DSTRKT
    }
}
