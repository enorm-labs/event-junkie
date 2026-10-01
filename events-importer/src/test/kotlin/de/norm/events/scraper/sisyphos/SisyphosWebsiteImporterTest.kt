package de.norm.events.scraper.sisyphos

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
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
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SisyphosWebsiteImporterTest {
    private lateinit var importer: SisyphosWebsiteImporter
    private val apiClient: ApiClient = mockk()
    private val htmlFetcher: HtmlFetcher = mockk()
    private val feedUrl = "https://www.sisyphos-berlin.net/collections/tickets/products.json"
    private val calendarUrl = "https://dashboard.sisyphos-berlin.net/kalender-media/events.json"

    private fun resource(path: String): String =
        javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    private val shopJson = resource("scraper/sisyphos/sisyphos-tickets.json")
    private val calendarJson = resource("scraper/sisyphos/sisyphos-calendar.json")
    private val sisyfanPage = Jsoup.parse(resource("scraper/sisyphos/sisyfan-home.html"), "https://sisy.fan/")

    /** A Wednesday, outside the sisy.fan window, with the calendar's NICHTGEBURTSTAG two days ahead. */
    private val wednesday = LocalDateTime.of(2026, 9, 30, 12, 0)

    /** The Saturday of the sisy.fan fixture's weekend, inside the window. */
    private val saturday = LocalDateTime.of(2026, 9, 26, 12, 0)

    private fun importerAt(time: LocalDateTime): SisyphosWebsiteImporter =
        SisyphosWebsiteImporter(apiClient, htmlFetcher, Clock.fixed(time.atZone(BERLIN).toInstant(), BERLIN))

    private suspend fun SisyphosWebsiteImporter.run(): ImportResult.Success {
        val result = importEvents(feedUrl)
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result
    }

    @BeforeEach
    fun setUp() {
        importer = importerAt(wednesday)
        coEvery { apiClient.fetchJson(calendarUrl) } returns calendarJson
        coEvery { apiClient.fetchJson(feedUrl) } returns shopJson
        coEvery { htmlFetcher.fetchDocument("https://sisy.fan/") } returns sisyfanPage
    }

    @Test
    fun `the calendar's later nights are imported with the shop's price joined in`() =
        runTest {
            val result = importer.run()
            result.events.map { it.sourceId } shouldBe
                listOf("sisyphos:2026-10-02", "sisyphos:2026-10-10", "sisyphos:2026-10-23", "sisyphos:2026-11-14", "sisyphos:2026-11-27", "sisyphos:2027-01-01")
            val generations = result.events.single { it.sourceId == "sisyphos:2026-10-10" }
            generations.startTime shouldBe LocalTime.of(14, 0)
            generations.pricePresale shouldBe BigDecimal("25.00")
            generations.ticketUrl shouldBe "https://www.sisyphos-berlin.net/products/generations-10-okt-2026"
            result.complete shouldBe true
            coVerify(exactly = 1) { apiClient.fetchJson(calendarUrl) }
            coVerify(exactly = 1) { apiClient.fetchJson(feedUrl) }
        }

    @Test
    fun `NICHTGEBURTSTAG opens on Friday at 22 00 and closes on Tuesday at 10 00`() =
        runTest {
            val weekend = importer.run().events.single { it.title == "SISY 54 - NICHTGEBURTSTAG" }
            weekend.eventDate shouldBe LocalDate.of(2026, 10, 2)
            weekend.startTime shouldBe LocalTime.of(22, 0)
            weekend.endDate shouldBe LocalDate.of(2026, 10, 6)
            weekend.endTime shouldBe LocalTime.of(10, 0)
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
    fun `an empty shop collection leaves the calendar nights without a price`() =
        runTest {
            coEvery { apiClient.fetchJson(feedUrl) } returns """{"products": []}"""
            val result = importer.run()
            result.events shouldHaveSize 6
            result.events.mapNotNull { it.pricePresale }.shouldBeEmpty()
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.SISYPHOS
    }

    @Test
    fun `outside the window sisy fan is not read`() =
        runTest {
            importer.run()
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `outside the window a night dated today or earlier is left out`() =
        runTest {
            importerAt(LocalDateTime.of(2026, 10, 11, 5, 0)).run().events.map { it.eventDate.toString() } shouldBe
                listOf("2026-10-23", "2026-11-14", "2026-11-27", "2027-01-01")
        }

    @Test
    fun `inside the window the weekend's calendar night takes the sisy fan line-up`() =
        runTest {
            val result = importerAt(saturday).run()
            result.events shouldHaveSize 9
            val weekend = result.events.single { it.sourceId == "sisyphos:2026-09-25" }
            weekend.title shouldBe "HAPPY RAVE HAPPY LIFE"
            weekend.startTime shouldBe LocalTime.of(22, 0)
            weekend.artists.shouldNotBeEmpty()
            weekend.lineupSourceUrl shouldBe "https://sisy.fan/events/from/25.09.2026/to/28.09.2026"
            coVerify(exactly = 1) { htmlFetcher.fetchDocument("https://sisy.fan/") }
        }

    @Test
    fun `a failed sisy fan fetch leaves out the nights dated today or earlier`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, "https://sisy.fan/")
            val result = importerAt(LocalDateTime.of(2026, 10, 10, 12, 0)).run()
            result.events.map { it.eventDate.toString() } shouldBe listOf("2026-10-23", "2026-11-14", "2026-11-27", "2027-01-01")
            result.complete shouldBe true
        }

    @Test
    fun `a calendar failure imports the shop and the weekends as an incomplete run`() =
        runTest {
            coEvery { apiClient.fetchJson(calendarUrl) } throws HttpFetchException(503, calendarUrl)
            val result = importerAt(saturday).run()
            result.events.map { it.sourceId } shouldBe listOf("sisyphos:2026-10-10", "sisyphos:2026-09-25")
            result.complete shouldBe false
        }

    @Test
    fun `outside the window a calendar failure imports the shop's later nights as an incomplete run`() =
        runTest {
            coEvery { apiClient.fetchJson(calendarUrl) } throws HttpFetchException(503, calendarUrl)
            val result = importer.run()
            result.events.map { it.sourceId } shouldBe listOf("sisyphos:2026-10-10")
            result.complete shouldBe false
        }

    @Test
    fun `a shop failure imports the calendar without prices as a complete run`() =
        runTest {
            coEvery { apiClient.fetchJson(feedUrl) } throws HttpFetchException(429, feedUrl)
            val result = importer.run()
            result.events shouldHaveSize 6
            result.events.mapNotNull { it.pricePresale }.shouldBeEmpty()
            result.complete shouldBe true
        }

    @Test
    fun `the stale cleanup stays windowed, so a complete run without the shop spares the shop-only nights`() {
        importer.listsWholeProgramme shouldBe false
    }

    @Test
    fun `a shop failure joins the shop nights of the last run`() =
        runTest {
            importer.run()
            coEvery { apiClient.fetchJson(feedUrl) } throws HttpFetchException(429, feedUrl)
            val generations = importer.run().events.single { it.sourceId == "sisyphos:2026-10-10" }
            generations.pricePresale shouldBe BigDecimal("25.00")
        }

    @Test
    fun `outside the window a calendar and shop failure fails the run with the shop's error`() =
        runTest {
            coEvery { apiClient.fetchJson(calendarUrl) } throws HttpFetchException(503, calendarUrl)
            coEvery { apiClient.fetchJson(feedUrl) } throws HttpFetchException(429, feedUrl)
            shouldThrow<HttpFetchException> { importer.importEvents(feedUrl) }.statusCode shouldBe 429
        }

    @Test
    fun `a run where all three sites fail fails with the shop's error`() =
        runTest {
            coEvery { apiClient.fetchJson(calendarUrl) } throws HttpFetchException(503, calendarUrl)
            coEvery { apiClient.fetchJson(feedUrl) } throws HttpFetchException(429, feedUrl)
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, "https://sisy.fan/")
            shouldThrow<HttpFetchException> { importerAt(saturday).importEvents(feedUrl) }.statusCode shouldBe 429
        }
}
