package de.norm.events.scraper.friedrichstadtpalast

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

/** Unit tests for [FriedrichstadtPalastWebsiteImporter]. */
class FriedrichstadtPalastWebsiteImporterTest {
    private lateinit var importer: FriedrichstadtPalastWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.palast.berlin/gastspiele/"

    private fun fixture() =
        javaClass.classLoader
            .getResourceAsStream("scraper/friedrichstadtpalast/friedrichstadtpalast-gastspiele.html")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = FriedrichstadtPalastWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture(), sourceUrl),
                etag = "\"palast-gastspiele\"",
                lastModified = "Sat, 17 Oct 2026 04:00:00 GMT"
            )
    }

    @Test
    fun `importEvents reads every performance from one page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 8
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"palast-gastspiele\""
            result.lastModified shouldBe "Sat, 17 Oct 2026 04:00:00 GMT"
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

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `nothing past the entry page is fetched`() {
        importer.fetchesBeyondEntryPage shouldBe false
    }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.FRIEDRICHSTADTPALAST
    }
}
