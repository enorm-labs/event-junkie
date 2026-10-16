package de.norm.events.scraper.wabe

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
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

class WabeWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = WabeWebsiteImporter(htmlFetcher)
    private val entryUrl = "https://www.wabe-berlin.info/"
    private val eventPageUrl = "https://www.wabe-berlin.info/okt-2026/3/"

    private fun monthUrl(month: String) = "https://www.wabe-berlin.info/$month-2026/"

    private fun fixture(
        name: String,
        url: String
    ): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/wabe/$name")!!
                .bufferedReader()
                .readText(),
            url
        )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetchDocument(entryUrl) } returns fixture("wabe-entry.html", entryUrl)
        listOf("okt", "nov", "dez").forEach { month ->
            coEvery { htmlFetcher.fetchDocument(monthUrl(month)) } returns fixture("wabe-$month-2026.html", monthUrl(month))
        }
        coEvery { htmlFetcher.fetchDocument(eventPageUrl) } returns fixture("wabe-event-okt-2026-3.html", eventPageUrl)
        coEvery { htmlFetcher.fetchDocument(match { it != entryUrl && it != eventPageUrl && !it.endsWith("-2026/") }) } throws
            HttpFetchException(404, "event page")
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.WABE
    }

    @Test
    fun `reads every month the navigation links and each night's own page`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 23
            result.etag.shouldBeNull()
            result.events.all { it.sourceId.startsWith(EventSource.WABE.sourceIdPrefix) } shouldBe true
            val enriched = result.events.single { it.sourceUrl == eventPageUrl }
            enriched.ticketUrl shouldBe "https://shop.entrello.app/de/wabe/events/ev_3GRgDeriIgGTiIuYPZ5te0zDNb5/tickets"
            enriched.detailUnavailable shouldBe false
        }

    @Test
    fun `flags a night whose own page fails and reads no page for a row without one`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.single { it.sourceUrl == "https://www.wabe-berlin.info/okt-2026/15/" }.detailUnavailable shouldBe true
            result.events.filter { it.sourceUrl == monthUrl("okt") }.none { it.detailUnavailable } shouldBe true
            coVerify(exactly = 0) { htmlFetcher.fetchDocument("https://www.wabe-berlin.info/open-mic/") }
        }

    @Test
    fun `keeps the other months when one fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(monthUrl("nov")) } throws HttpFetchException(503, monthUrl("nov"))

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 20
            result.complete shouldBe false
        }

    @Test
    fun `returns no events when the entry page links no month`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns Jsoup.parse("<p>Wartung</p>", entryUrl)

            importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events shouldHaveSize 0
        }
}
