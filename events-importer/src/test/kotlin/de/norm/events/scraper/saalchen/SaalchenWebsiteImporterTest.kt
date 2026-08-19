package de.norm.events.scraper.saalchen

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [SaalchenWebsiteImporter].
 */
class SaalchenWebsiteImporterTest {
    private lateinit var importer: SaalchenWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.holzmarkt.com/kalender"

    @BeforeEach
    fun setUp() {
        importer = SaalchenWebsiteImporter(htmlFetcher)
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/saalchen/saalchen-kalender.html")!!
                .bufferedReader()
                .readText()

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(html, sourceUrl),
                etag = "\"holzmarkt-etag\"",
                lastModified = "Sat, 01 Aug 2026 11:00:00 GMT"
            )
    }

    @Test
    fun `importEvents keeps only this venue's rows`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 8
            result.events.first().eventDate shouldBe LocalDate.of(2026, 8, 14)
            result.events.last().eventDate shouldBe LocalDate.of(2026, 11, 27)
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"holzmarkt-etag\""
            result.lastModified shouldBe "Sat, 01 Aug 2026 11:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns empty list for a calendar without this venue`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><div class='view-content'></div></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.SAALCHEN
    }
}
