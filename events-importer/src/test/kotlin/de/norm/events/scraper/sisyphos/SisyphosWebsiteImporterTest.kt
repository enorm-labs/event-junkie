package de.norm.events.scraper.sisyphos

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
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
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDateTime

class SisyphosWebsiteImporterTest {
    private lateinit var importer: SisyphosWebsiteImporter
    private val apiClient: ApiClient = mockk()
    private val htmlFetcher: HtmlFetcher = mockk()
    private val feedUrl = "https://www.sisyphos-berlin.net/collections/tickets/products.json"

    private val fixtureJson: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/sisyphos/sisyphos-tickets.json")!!
            .bufferedReader()
            .readText()

    private val sisyfanPage =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/sisyphos/sisyfan-home.html")!!
                .bufferedReader()
                .readText(),
            "https://sisy.fan/"
        )

    /** A Wednesday, outside the sisy.fan window and before the fixture's 10 October night. */
    private val wednesday = LocalDateTime.of(2026, 9, 30, 12, 0)

    private fun importerAt(time: LocalDateTime): SisyphosWebsiteImporter =
        SisyphosWebsiteImporter(apiClient, htmlFetcher, Clock.fixed(time.atZone(BERLIN).toInstant(), BERLIN))

    @BeforeEach
    fun setUp() {
        importer = importerAt(wednesday)
        coEvery { apiClient.fetchJson(feedUrl) } returns fixtureJson
        coEvery { htmlFetcher.fetchDocument("https://sisy.fan/") } returns sisyfanPage
    }

    @Test
    fun `importEvents fetches the configured feed and returns its dated tickets`() =
        runTest {
            val result = importer.importEvents(feedUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 1
            result.events.single().sourceId shouldBe "sisyphos:generations-10-okt-2026"
            coVerify(exactly = 1) { apiClient.fetchJson(feedUrl) }
        }

    @Test
    fun `importEvents reports no conditional-cache headers`() =
        runTest {
            val result = importer.importEvents(feedUrl, etag = "W/\"stale\"", lastModified = "yesterday")
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success for an empty collection`() =
        runTest {
            coEvery { apiClient.fetchJson(feedUrl) } returns """{"products": []}"""

            val result = importer.importEvents(feedUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.SISYPHOS
    }

    @Test
    fun `outside the window sisy fan is not read`() =
        runTest {
            importer.importEvents(feedUrl)
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `outside the window a shop night dated today or earlier is left out`() =
        runTest {
            val result = importerAt(LocalDateTime.of(2026, 10, 11, 5, 0)).importEvents(feedUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `inside the window the shop nights and the sisy fan weekends are both returned`() =
        runTest {
            val result = importerAt(LocalDateTime.of(2026, 9, 26, 12, 0)).importEvents(feedUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.sourceId } shouldBe listOf("sisyphos:generations-10-okt-2026", "sisyphos:weekend-2026-09-25")
            coVerify(exactly = 1) { htmlFetcher.fetchDocument("https://sisy.fan/") }
        }

    @Test
    fun `a failed sisy fan fetch keeps the shop's later nights`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, "https://sisy.fan/")
            val result = importerAt(LocalDateTime.of(2026, 10, 10, 12, 0)).importEvents(feedUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `inside the window a shop failure still imports the sisy fan weekends, as an incomplete run`() =
        runTest {
            coEvery { apiClient.fetchJson(feedUrl) } throws HttpFetchException(429, feedUrl)
            val result = importerAt(LocalDateTime.of(2026, 9, 26, 12, 0)).importEvents(feedUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.sourceId } shouldBe listOf("sisyphos:weekend-2026-09-25")
            result.complete shouldBe false
        }

    @Test
    fun `a shop failure in the window merges into the shop nights of the last run`() =
        runTest {
            val saturday = importerAt(LocalDateTime.of(2026, 9, 26, 12, 0))
            saturday.importEvents(feedUrl)
            coEvery { apiClient.fetchJson(feedUrl) } throws HttpFetchException(429, feedUrl)

            val result = saturday.importEvents(feedUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.sourceId } shouldBe listOf("sisyphos:generations-10-okt-2026", "sisyphos:weekend-2026-09-25")
        }

    @Test
    fun `outside the window a shop failure fails the run`() =
        runTest {
            coEvery { apiClient.fetchJson(feedUrl) } throws HttpFetchException(429, feedUrl)
            shouldThrow<HttpFetchException> { importer.importEvents(feedUrl) }
        }

    @Test
    fun `a run where both sites fail fails with the shop's error`() =
        runTest {
            coEvery { apiClient.fetchJson(feedUrl) } throws HttpFetchException(429, feedUrl)
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, "https://sisy.fan/")
            val error = shouldThrow<HttpFetchException> { importerAt(LocalDateTime.of(2026, 9, 26, 12, 0)).importEvents(feedUrl) }
            error.statusCode shouldBe 429
        }
}
