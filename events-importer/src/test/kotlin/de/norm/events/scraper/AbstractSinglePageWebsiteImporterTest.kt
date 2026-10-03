package de.norm.events.scraper

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Unit tests for [AbstractSinglePageWebsiteImporter]'s cookies and paged listing, on synthetic pages. */
class AbstractSinglePageWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val entryUrl = "https://venue.example/events"

    private fun page(
        url: String,
        vararg ids: String,
        next: String? = null
    ): Document =
        Jsoup.parse(
            "<ul>${ids.joinToString("") { "<li id='$it'></li>" }}</ul>" + next?.let { "<a class='next' href='$it'></a>" }.orEmpty(),
            url
        )

    private fun scrape(
        document: Document,
        url: String
    ): List<ScrapedEvent> =
        document.select("li").map {
            ScrapedEvent(title = it.id(), eventDate = LocalDate.of(2026, 10, 1), sourceUrl = url, sourceId = it.id())
        }

    private inner class OnePage : AbstractSinglePageWebsiteImporter(htmlFetcher, "One Page", ::scrape) {
        override val eventSource: EventSource = EventSource.ROSA
        override val cookies: Map<String, String> = mapOf("gate" to "1")
    }

    private inner class Paged : AbstractSinglePageWebsiteImporter(htmlFetcher, "Paged", ::scrape) {
        override val eventSource: EventSource = EventSource.SCHOKOLADEN
        override val maxListingPages: Int = 3

        override fun nextListingPage(
            document: Document,
            url: String
        ): String? = document.nextPageUrl("a.next")
    }

    private fun stubEntry(document: Document) {
        coEvery { htmlFetcher.fetch(entryUrl, any(), any(), any()) } returns FetchResult.Success(document, "\"v1\"", null)
    }

    @Test
    fun `reads the entry page alone, sends its cookies, and keeps the entry page's validators`() =
        runTest {
            stubEntry(page(entryUrl, "a", next = "?page=2"))

            val result = OnePage().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.map { it.sourceId } shouldContainExactly listOf("a")
            result.etag shouldBe "\"v1\""
            result.complete shouldBe true
            OnePage().fetchesBeyondEntryPage shouldBe false
            coVerify { htmlFetcher.fetch(entryUrl, null, null, mapOf("gate" to "1")) }
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `walks a paged listing, keeps an event once across a page boundary, and is never skipped on a 304`() =
        runTest {
            stubEntry(page(entryUrl, "a", "b", next = "?page=2"))
            coEvery { htmlFetcher.fetchDocument("$entryUrl?page=2") } returns page("$entryUrl?page=2", "b", "c")

            val result = Paged().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.map { it.sourceId } shouldContainExactly listOf("a", "b", "c")
            result.complete shouldBe true
            Paged().fetchesBeyondEntryPage shouldBe true
        }

    @Test
    fun `reports a listing cut at its page cap as incomplete`() =
        runTest {
            stubEntry(page(entryUrl, "a", next = "?page=2"))
            coEvery { htmlFetcher.fetchDocument(any()) } answers {
                val n = firstArg<String>().pageNumber()
                page(firstArg(), "e$n", next = "?page=${n + 1}")
            }

            Paged().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().complete shouldBe false
        }
}
