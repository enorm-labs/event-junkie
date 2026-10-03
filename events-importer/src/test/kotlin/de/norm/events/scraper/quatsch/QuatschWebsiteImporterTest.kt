package de.norm.events.scraper.quatsch

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class QuatschWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = QuatschWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://quatsch-comedy-club.de/tickets/"
    private val ajaxUrl = "https://quatsch-comedy-club.de/wp-admin/admin-ajax.php"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/quatsch/$name")!!
            .bufferedReader()
            .readText()

    private fun dayForm(date: String) =
        mapOf(
            "action" to "eventim_calendar",
            "security" to "a732979e64",
            "date" to date,
            "format" to "arrows",
            "language" to "de",
            "location" to "Berlin"
        )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetchDocument(sourceUrl) } returns Jsoup.parse(fixture("quatsch-tickets.html"), sourceUrl)
        coEvery { htmlFetcher.postForm(ajaxUrl, any()) } returns fixture("quatsch-day-no-show.json")
        coEvery { htmlFetcher.postForm(ajaxUrl, dayForm("4.10.2026")) } returns fixture("quatsch-day-house-shows.json")
        coEvery { htmlFetcher.postForm(ajaxUrl, dayForm("6.10.2026")) } returns fixture("quatsch-day-guest-show.json")
    }

    @Test
    fun `eventSource identifies this importer as the Quatsch Comedy Club`() {
        importer.eventSource shouldBe EventSource.QUATSCH
        importer.fetchesBeyondEntryPage shouldBe true
    }

    @Test
    fun `asks the calendar once for every day the page lists, in the page's city, and gathers the shows`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.sourceId } shouldBe
                listOf("quatsch:eventim-2242", "quatsch:eventim-2202", "quatsch:2026-10-06-filippo-giardina-la-banalita-del-male-it")
            result.complete shouldBe true
            result.etag shouldBe null
            coVerify(exactly = 66) { htmlFetcher.postForm(ajaxUrl, any()) }
            coVerify(exactly = 1) { htmlFetcher.postForm(ajaxUrl, dayForm("3.10.2026")) }
        }

    @Test
    fun `a day that fails keeps the other days' shows and reports the walk incomplete`() =
        runTest {
            coEvery { htmlFetcher.postForm(ajaxUrl, dayForm("6.10.2026")) } throws HttpFetchException(503, ajaxUrl)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.sourceId } shouldBe listOf("quatsch:eventim-2242", "quatsch:eventim-2202")
            result.complete shouldBe false
        }

    @Test
    fun `a tickets page without the calendar settings fails the run instead of reporting an empty programme`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(sourceUrl) } returns Jsoup.parse("<html><body></body></html>", sourceUrl)

            shouldThrow<IllegalStateException> { importer.importEvents(sourceUrl) }
            coVerify(exactly = 0) { htmlFetcher.postForm(any(), any()) }
        }
}
