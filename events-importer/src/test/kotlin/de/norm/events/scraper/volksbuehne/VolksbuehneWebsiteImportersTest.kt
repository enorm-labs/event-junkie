package de.norm.events.scraper.volksbuehne

import de.norm.events.event.EventType
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
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test

class VolksbuehneWebsiteImportersTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val url = "https://www.volksbuehne-berlin.de/"

    @Test
    fun `reads the Spielplan and enriches each row from its performance page`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
                FetchResult.Success(document = document("volksbuehne-spielplan.html", url), etag = "\"v1\"", lastModified = null)
            coEvery { htmlFetcher.fetchDocument(match { "/en/" !in it }) } answers {
                document("volksbuehne-event-a-year-without-summer.html", firstArg())
            }
            coEvery { htmlFetcher.fetchDocument(match { "/en/" in it }) } answers { document("volksbuehne-event-space-afrika-en.html", firstArg()) }

            val result = VolksbuehnePraterWebsiteImporter(htmlFetcher).importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"v1\""
            result.events shouldHaveSize 16
            result.events.all { it.description?.startsWith("BEATRICE CORDUA") == true } shouldBe true
            result.events.first { it.title == "Perfection" }.eventType shouldBe EventType.SHOW.name
            result.events.first { it.title == "Perfection" }.typeIsFallback shouldBe false
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.NotModified

            VolksbuehneWebsiteImporter(htmlFetcher).importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for an empty Spielplan`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", url), etag = null, lastModified = null)

            val result = VolksbuehneWebsiteImporter(htmlFetcher).importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldBe emptyList()
        }

    @Test
    fun `each importer names its own source and reads beyond the entry page`() {
        VolksbuehneWebsiteImporter(htmlFetcher).eventSource shouldBe EventSource.VOLKSBUEHNE
        VolksbuehnePraterWebsiteImporter(htmlFetcher).eventSource shouldBe EventSource.VOLKSBUEHNE_PRATER
        VolksbuehneWebsiteImporter(htmlFetcher).fetchesBeyondEntryPage shouldBe true
    }

    private fun document(
        name: String,
        baseUri: String
    ): Document = Jsoup.parse(requireNotNull(javaClass.classLoader.getResourceAsStream("scraper/volksbuehne/$name")).bufferedReader().readText(), baseUri)
}
