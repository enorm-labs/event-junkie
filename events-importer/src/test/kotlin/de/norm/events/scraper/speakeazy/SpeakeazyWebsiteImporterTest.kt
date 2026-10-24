package de.norm.events.scraper.speakeazy

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SpeakeazyWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = SpeakeazyWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://www.speakeazyberlin.de/events"

    private fun fixture(
        name: String,
        url: String
    ): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/speakeazy/$name")!!
                .bufferedReader()
                .readText(),
            url
        )

    private val bluesUrl = "$sourceUrl/the-sixth-dimension-blues"

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = fixture("speakeazy-events.html", sourceUrl),
                etag = "\"abc\"",
                lastModified = "Sat, 24 Oct 2026 10:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(any()) } returns Jsoup.parse("<html><body></body></html>", sourceUrl)
        coEvery { htmlFetcher.fetchDocument(bluesUrl) } returns fixture("speakeazy-event-the-sixth-dimension-blues.html", bluesUrl)
    }

    @Test
    fun `imports the upcoming nights with the page's validators and each event's page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 6
            result.etag shouldBe "\"abc\""
            result.lastModified shouldBe "Sat, 24 Oct 2026 10:00:00 GMT"
            val blues = result.events.single { it.sourceUrl == bluesUrl }
            blues.priceBoxOffice shouldBe BigDecimal("16")
            blues.detailUnavailable shouldBe false
            coVerify(exactly = 6) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `flags a night whose page fails, so the stored blurb and price stay`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(bluesUrl) } throws IllegalStateException("boom")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val blues = result.events.single { it.sourceUrl == bluesUrl }
            blues.detailUnavailable shouldBe true
            blues.description.shouldBeNull()
            result.events shouldHaveSize 6
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for a page without a listing`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.SPEAKEAZY
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe true
    }
}
