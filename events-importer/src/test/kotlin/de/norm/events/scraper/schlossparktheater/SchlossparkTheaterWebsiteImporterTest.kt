package de.norm.events.scraper.schlossparktheater

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
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
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SchlossparkTheaterWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = SchlossparkTheaterWebsiteImporter(htmlFetcher)

    private val site = "https://www.schlossparktheater.de/"
    private val entryUrl = "${site}spielplan/kalender.html"
    private val putschUrl = "${site}produktionen/putsch.html"

    private fun monthUrl(month: String) = "${site}spielplan/$month.html"

    private fun fixture(
        name: String,
        url: String
    ): Document = Jsoup.parse(javaClass.classLoader.getResource("scraper/schlossparktheater/schlossparktheater-$name.html")!!.readText(), url)

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse("<html><body></body></html>", firstArg<String>()) }
        coEvery { htmlFetcher.fetchDocument(entryUrl) } returns fixture("kalender", entryUrl)
        coEvery { htmlFetcher.fetchDocument(monthUrl("oktober-2026")) } returns fixture("kalender", monthUrl("oktober-2026"))
        coEvery { htmlFetcher.fetchDocument(monthUrl("november-2026")) } returns fixture("november-2026", monthUrl("november-2026"))
        coEvery { htmlFetcher.fetchDocument(monthUrl("februar-2027")) } returns fixture("februar-2027", monthUrl("februar-2027"))
        coEvery { htmlFetcher.fetchDocument(putschUrl) } returns fixture("putsch", putschUrl)
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.SCHLOSSPARK_THEATER
    }

    @Test
    fun `reads every month the calendar links, resolved against the site root`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            // 15 October + 39 November + 35 February performances; the other months are empty here.
            result.events shouldHaveSize 89
            result.complete shouldBe true
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(monthUrl("september-2027")) }
            coVerify(exactly = 0) { htmlFetcher.fetchDocument("${site}spielplan/spielplan/november-2026.html") }
        }

    @Test
    fun `reads each production page once and gives its text to every performance`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            val putsch = result.events.filter { it.sourceUrl == putschUrl }
            putsch shouldHaveSize 2
            putsch.all { it.description?.startsWith("Was passiert, wenn Satire zur Macht wird?") == true } shouldBe true
            putsch.all { !it.detailUnavailable } shouldBe true
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(putschUrl) }
        }

    @Test
    fun `a production page that yields no text flags its performances`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            val other = result.events.first { it.sourceUrl != putschUrl }
            other.detailUnavailable shouldBe true
            other.description.shouldBeNull()
        }

    @Test
    fun `keeps the other months when one fails, and reports the run incomplete`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(monthUrl("november-2026")) } throws HttpFetchException(503, monthUrl("november-2026"))

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 50
            result.complete shouldBe false
        }

    @Test
    fun `an entry page that links no months is an empty success`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns Jsoup.parse("<html><body></body></html>", entryUrl)

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 0
        }

    @Test
    fun `month links resolve against the page base`() {
        fixture("kalender", entryUrl).baseUri() shouldStartWith site
    }
}
