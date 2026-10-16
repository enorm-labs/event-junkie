package de.norm.events.scraper.zebranotheater

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** Unit tests for [ZebranoTheaterWebsiteImporter]: one listing fetch, then the page of each evening that links one. */
class ZebranoTheaterWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = ZebranoTheaterWebsiteImporter(htmlFetcher)
    private val listingUrl = "https://www.zebrano-theater.de/programm.html"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/zebranotheater/$name")!!
            .bufferedReader()
            .readText()

    private fun stubListing() {
        coEvery { htmlFetcher.fetch(listingUrl, any(), any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("zebranotheater-programm.html"), listingUrl),
                etag = "\"16b49-65dc9e685cec5\"",
                lastModified = "Wed, 14 Oct 2026 09:53:17 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ZEBRANO_THEATER
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe true
    }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any(), any()) } returns FetchResult.NotModified
            importer.importEvents(listingUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `reads the page of each evening that links one and propagates conditional headers`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse(fixture("zebranotheater-20261125.html"), firstArg<String>()) }

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 36
            result.events.first().description shouldStartWith "Beppe Gambetta"
            result.etag shouldBe "\"16b49-65dc9e685cec5\""
            result.lastModified shouldBe "Wed, 14 Oct 2026 09:53:17 GMT"
            val announced = result.events.single { it.sourceUrl == listingUrl && it.title.startsWith("Johannes Kirchberg") }
            announced.description.shouldBeNull()
            announced.detailUnavailable shouldBe false
            coVerify(exactly = 33) { htmlFetcher.fetchDocument(any()) }
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(listingUrl) }
        }

    @Test
    fun `flags the listing row when the evening's page fails`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("boom")

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 36
            result.events.first().detailUnavailable shouldBe true
            result.events.first().title shouldBe "Susanne M. Riedel und Andreas Scheffler: Tollste Texte, liebste Lieder - Vol. 3"
        }

    @Test
    fun `returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", listingUrl), etag = null, lastModified = null)

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
