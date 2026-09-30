package de.norm.events.scraper.cosmiccomedy

import de.norm.events.event.EventType
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [CosmicComedyWebsiteImporter].
 *
 * Uses static JSON fixtures and a mocked [ApiClient] for deterministic, offline-safe testing
 * without real HTTP requests.
 */
class CosmicComedyWebsiteImporterTest {
    private lateinit var importer: CosmicComedyWebsiteImporter
    private val apiClient: ApiClient = mockk()
    private val sourceUrl = "https://comedyclubberlin.com/wp-json/tribe/events/v1/events"
    private val firstPageUrl = "$sourceUrl?per_page=50"

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/cosmiccomedy/$name")!!
            .bufferedReader()
            .readText()

    /** The cursor the first fixture page hands back, which the importer must follow verbatim. */
    private val secondPageUrl: String by lazy {
        CosmicComedyApiScraper().scrapePage(readFixture("cosmiccomedy-events-page1.json")).nextPageUrl!!
    }

    @BeforeEach
    fun setUp() {
        importer = CosmicComedyWebsiteImporter(apiClient)
        coEvery { apiClient.fetchJson(firstPageUrl) } returns readFixture("cosmiccomedy-events-page1.json")
        coEvery { apiClient.fetchJson(secondPageUrl) } returns readFixture("cosmiccomedy-events-page2.json")
    }

    @Test
    fun `importEvents walks the API's cursor to the end of the programme`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 57

            coVerify(exactly = 1) { apiClient.fetchJson(firstPageUrl) }
            coVerify(exactly = 1) { apiClient.fetchJson(secondPageUrl) }
            coVerify(exactly = 2) { apiClient.fetchJson(any()) }
        }

    @Test
    fun `importEvents keeps the first page when a later page fails`() =
        runTest {
            coEvery { apiClient.fetchJson(secondPageUrl) } throws RuntimeException("connection reset")

            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 50
            result.complete shouldBe false
        }

    @Test
    fun `importEvents asks for the largest page the plugin allows`() =
        runTest {
            importer.importEvents(sourceUrl)
            coVerify { apiClient.fetchJson("$sourceUrl?per_page=50") }
        }

    @Test
    fun `importEvents appends to a query the configured endpoint already carries`() =
        runTest {
            val filtered = "$sourceUrl?status=publish"
            coEvery { apiClient.fetchJson("$filtered&per_page=50") } returns readFixture("cosmiccomedy-events-page2.json")

            val result = importer.importEvents(filtered)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 7
        }

    @Test
    fun `importEvents maps the events it collected`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val friday = result.events.first { it.sourceId == "cosmic_comedy:comedy-pizza-and-shots-showcase-friday-17" }

            friday.title shouldBe "Comedy, Pizza and Shots – SHOWCASE FRIDAY"
            friday.eventType shouldBe EventType.SHOW.name
            friday.eventDate shouldBe LocalDate.of(2026, 8, 7)
            friday.pricePresale.shouldBeNull()
        }

    @Test
    fun `importEvents states no conditional headers, the API window moving with today`() =
        runTest {
            val result = importer.importEvents(sourceUrl, etag = "\"old\"", lastModified = "Mon, 03 Aug 2026 09:00:00 GMT")
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents stops at a page it cannot parse rather than looping`() =
        runTest {
            coEvery { apiClient.fetchJson(firstPageUrl) } returns "not json at all"

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
            coVerify(exactly = 1) { apiClient.fetchJson(any()) }
        }

    @Test
    fun `importEvents propagates a failed request`() =
        runTest {
            coEvery { apiClient.fetchJson(firstPageUrl) } throws HttpFetchException(503, firstPageUrl)

            shouldThrow<HttpFetchException> { importer.importEvents(sourceUrl) }
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.COSMIC_COMEDY
    }
}
