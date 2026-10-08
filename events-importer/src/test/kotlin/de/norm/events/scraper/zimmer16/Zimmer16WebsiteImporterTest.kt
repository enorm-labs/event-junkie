package de.norm.events.scraper.zimmer16

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalTime

class Zimmer16WebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = Zimmer16WebsiteImporter(htmlFetcher)
    private val url = "https://zimmer16.com/"
    private val bourbonUrl = "https://www.yesticket.org/event/de/claude-bourbon-erwachsene-10-10-26"

    @Test
    fun `reads each card's event page and flags the ones that fail`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse(fixture("zimmer16-home.html"), url), etag = null, lastModified = null)
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, "https://www.yesticket.org/")
            coEvery { htmlFetcher.fetchDocument(bourbonUrl) } returns Jsoup.parse(fixture("yesticket-claude-bourbon.html"), bourbonUrl)

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 20
            result.events.first { it.sourceUrl == bourbonUrl }.startTime shouldBe LocalTime.of(20, 0)
            result.events.count { it.detailUnavailable } shouldBe 19
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.ZIMMER_16
        importer.listsWholeProgramme shouldBe false
        importer.fetchesBeyondEntryPage shouldBe true
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/zimmer16/$name")!!
            .bufferedReader()
            .readText()
}
