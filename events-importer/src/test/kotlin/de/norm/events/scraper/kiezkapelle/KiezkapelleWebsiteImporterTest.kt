package de.norm.events.scraper.kiezkapelle

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for [KiezkapelleWebsiteImporter]. */
class KiezkapelleWebsiteImporterTest {
    private lateinit var importer: KiezkapelleWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.kiezkapelle.de/programm/"

    private fun fixture() =
        javaClass.classLoader
            .getResourceAsStream("scraper/kiezkapelle/kiezkapelle-programm.html")
            .let { requireNotNull(it) }
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = KiezkapelleWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture(), sourceUrl),
                etag = "\"kiezkapelle-1\"",
                lastModified = "Sat, 17 Oct 2026 08:00:00 GMT"
            )
    }

    @Test
    fun `importEvents reads the programme page and keeps nothing out of scope`() =
        runTest {
            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.shouldBeEmpty()
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.etag shouldBe "\"kiezkapelle-1\""
            result.lastModified shouldBe "Sat, 17 Oct 2026 08:00:00 GMT"
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
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            importer
                .importEvents(sourceUrl)
                .shouldBeInstanceOf<ImportResult.Success>()
                .events
                .shouldBeEmpty()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.KIEZKAPELLE
    }
}
