package de.norm.events.scraper.pastiche

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalTime

class PasticheWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = PasticheWebsiteImporter(htmlFetcher)
    private val url = "https://pasticheinternational.com/programme-1"
    private val ramziUrl = "https://pasticheinternational.com/ramzi-i-live-i-22-10"

    @Test
    fun `reads each event's page and flags the ones that fail`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
                FetchResult.Success(document = fixtureDocument("pastiche/pastiche-programme.html", url), etag = "\"v1\"", lastModified = null)
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, "https://pasticheinternational.com/")
            stubFetcher(ramziUrl to "pastiche/pastiche-ramzi.html", fetcher = htmlFetcher)

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"v1\""
            result.events.map { it.detailUnavailable } shouldBe listOf(false, true)
            result.events
                .first()
                .imageUrl
                .orEmpty() shouldStartWith "https://freight.cargo.site/"
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(url) }
        }

    @Test
    fun `stores the event page's start over the programme's, and keeps a stored start when the page fails`() =
        runTest {
            val page =
                """<div class="pages"><bodycopy><h1>Thurs, 22nd October<br />20:00 - 22:30</h1>""" +
                    """<column-set><column-unit slot="1">Text.</column-unit></column-set></bodycopy></div>"""
            stubFetcher(url to "pastiche/pastiche-programme.html", fetcher = htmlFetcher)
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, "https://pasticheinternational.com/")
            coEvery { htmlFetcher.fetchDocument(ramziUrl) } returns Jsoup.parse(page, ramziUrl)

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val (read, failed) = result.events
            read.startTime shouldBe LocalTime.of(20, 0)
            read.endTime shouldBe LocalTime.of(22, 30)
            failed.detailUnavailable shouldBe true
            failed.detailPageOwns shouldContain ScrapedField.START_TIME
        }

    @Test
    fun `does not read the programme again for a row without a button`() =
        runTest {
            val html =
                """<div class="pages"><column-set><column-unit slot="0">Sat, 24th Oct</column-unit>""" +
                    """<column-unit slot="1"><b>Someone</b></column-unit><column-unit slot="2">Text</column-unit></column-set></div>"""
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.Success(Jsoup.parse(html, url), null, null)

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.single().sourceUrl shouldBe url
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `handles an empty page`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.Success(Jsoup.parse("<html></html>", url), null, null)

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.PASTICHE
        importer.listsWholeProgramme shouldBe false
        importer.fetchesBeyondEntryPage shouldBe true
    }
}
