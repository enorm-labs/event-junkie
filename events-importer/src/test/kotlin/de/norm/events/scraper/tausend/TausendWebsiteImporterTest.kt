package de.norm.events.scraper.tausend

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TausendWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = TausendWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://tausendberlin.com/lineup/"
    private val englishUrl = "https://tausendberlin.com/en/lineup/"

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("tausend-overview.html"), sourceUrl),
                etag = "\"tausend\"",
                lastModified = "Thu, 08 Oct 2026 03:38:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(englishUrl) } returns Jsoup.parse(fixture("tausend-overview-en.html"), englishUrl)
    }

    @Test
    fun `imports the lineup with the English text as the second language`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 18
            result.etag shouldBe "\"tausend\""
            result.lastModified shouldBe "Thu, 08 Oct 2026 03:38:00 GMT"
            val night = result.events.first { it.sourceId == "tausend:2026-10-09-carla-valenti" }
            night.descriptionAlt!! shouldStartWith "The Chilean DJ and producer"
        }

    @Test
    fun `keeps the German text when the English page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(englishUrl) } throws HttpFetchException(503, englishUrl)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 18
            result.events.forEach { it.descriptionAlt.shouldBeNull() }
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for a page without a lineup`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.TAUSEND
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe true
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/tausend/$name")!!
            .bufferedReader()
            .readText()
}
