package de.norm.events.scraper.wuehlmaeuse

import de.norm.events.event.EventStatus
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class WuehlmaeuseWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = WuehlmaeuseWebsiteImporter(apiClient, htmlFetcher)
    private val sourceUrl = "https://wuehlmaeuse.de/wp-json/wc/store/v1/products"
    private val page1 = "$sourceUrl?per_page=100&page=1"
    private val page2 = "$sourceUrl?per_page=100&page=2"

    private fun readFixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("scraper/wuehlmaeuse/$name")!!
            .bufferedReader()
            .readText()

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(readFixture(name), url)

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(page1) } returns readFixture("wuehlmaeuse-shop-page1.json")
        coEvery { apiClient.fetchJson(page2) } returns readFixture("wuehlmaeuse-shop-last.json")
        coEvery { htmlFetcher.fetchDocument(any()) } answers { loadDocument("wuehlmaeuse-production-placeholder.html", firstArg()) }
        coEvery { htmlFetcher.fetchDocument(RUDELSINGEN) } answers { loadDocument("wuehlmaeuse-production-rudelsingen.html", firstArg()) }
    }

    @Test
    fun `gives every performance of a production the text of its page, read once`() =
        runTest {
            coEvery { apiClient.fetchJson(page1) } returns readFixture("wuehlmaeuse-shop-rudelsingen.json")

            val events = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>().events

            events.map { it.eventDate } shouldBe listOf(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26))
            events.forEach {
                it.description.shouldNotBeNull() shouldStartWith "David Rauterberg und Martin Hunger präsentieren die schönsten Lieder zum Mitsingen"
                it.detailUnavailable shouldBe false
            }
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(RUDELSINGEN) }
            coVerify(exactly = 1) { apiClient.fetchJson(any()) }
        }

    @Test
    fun `flags a production whose page fails, so the upsert keeps its stored text`() =
        runTest {
            coEvery { apiClient.fetchJson(page1) } returns readFixture("wuehlmaeuse-shop-rudelsingen.json")
            coEvery { htmlFetcher.fetchDocument(RUDELSINGEN) } throws HttpFetchException(503, RUDELSINGEN)

            val events = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>().events

            events shouldHaveSize 2
            events.forEach {
                it.description.shouldBeNull()
                it.detailUnavailable shouldBe true
            }
        }

    @Test
    fun `keeps the cancelled status the shop states`() =
        runTest {
            val events = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>().events

            events.filter { it.sourceUrl.endsWith("-abgesagt") }.map { it.status }.distinct() shouldBe listOf(EventStatus.CANCELLED.name)
        }

    @Test
    fun `asks for the next page until one comes back short`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.complete shouldBe true
            result.events.isNotEmpty() shouldBe true
            result.events.size shouldBe
                result.events
                    .map { it.sourceId }
                    .distinct()
                    .size
            coVerify(exactly = 2) { apiClient.fetchJson(any()) }
        }

    @Test
    fun `keeps the first page when the second fails`() =
        runTest {
            coEvery { apiClient.fetchJson(page2) } throws HttpFetchException(503, page2)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.complete shouldBe false
            result.events.isNotEmpty() shouldBe true
        }

    @Test
    fun `returns no events for an empty shop`() =
        runTest {
            coEvery { apiClient.fetchJson(page1) } returns "[]"

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `fetches unconditionally, because the production pages lie past the shop`() {
        importer.fetchesBeyondEntryPage shouldBe true
    }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.WUEHLMAEUSE
    }

    private companion object {
        const val RUDELSINGEN = "https://wuehlmaeuse.de/veranstaltung/rudelsingen"
    }
}
