package de.norm.events.scraper

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [scrapeListingPages], on synthetic pages: each lists its events as `<li id>` and
 * links the next page as `a.next`.
 */
class PagedListingTest {
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

    private suspend fun walk(
        first: Document,
        maxPages: Int = 5
    ) = htmlFetcher.scrapeListingPages(EventSource.SCHOKOLADEN, first, entryUrl, maxPages, { document, _ -> document.nextPageUrl("a.next") }, ::scrape)

    @Test
    fun `reads every page in order until the last renders no next link`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument("$entryUrl?page=2") } returns page("$entryUrl?page=2", "c", "d", next = "?page=3")
            coEvery { htmlFetcher.fetchDocument("$entryUrl?page=3") } returns page("$entryUrl?page=3", "e")

            val listing = walk(page(entryUrl, "a", "b", next = "?page=2"))

            listing.events.map { it.sourceId } shouldContainExactly listOf("a", "b", "c", "d", "e")
            listing.complete shouldBe true
        }

    @Test
    fun `keeps an event once when it shifts across a page boundary`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument("$entryUrl?page=2") } returns page("$entryUrl?page=2", "b", "c")

            walk(page(entryUrl, "a", "b", next = "?page=2")).events.map { it.sourceId } shouldContainExactly listOf("a", "b", "c")
        }

    @Test
    fun `keeps the pages read when a later page fails, and reports the walk incomplete`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument("$entryUrl?page=2") } returns page("$entryUrl?page=2", "c", next = "?page=3")
            coEvery { htmlFetcher.fetchDocument("$entryUrl?page=3") } throws HttpFetchException(503, "$entryUrl?page=3")

            val listing = walk(page(entryUrl, "a", "b", next = "?page=2"))

            listing.events.map { it.sourceId } shouldContainExactly listOf("a", "b", "c")
            listing.complete shouldBe false
        }

    @Test
    fun `stops at the page cap, and reports the walk incomplete`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(any()) } answers { page(firstArg(), firstArg<String>().substringAfterLast('='), next = "?page=9") }

            val listing = walk(page(entryUrl, "a", next = "?page=2"), maxPages = 2)

            listing.events.map { it.sourceId } shouldContainExactly listOf("a", "2")
            listing.complete shouldBe false
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `walkListingPages reads any page type and stops at a failed page`() =
        runTest {
            val pages = mapOf("p2" to listOf("b"), "p3" to listOf("c"))
            val walked =
                walkListingPages(EventSource.MATRIX, listOf("a"), "p1", 10, { url -> pages[url] ?: error("unreachable $url") }) { items, url ->
                    ListingPage(items, mapOf("p1" to "p2", "p2" to "p3", "p3" to "p4")[url])
                }

            walked.items shouldContainExactly listOf("a", "b", "c")
            walked.pages shouldBe 3
            walked.complete shouldBe false
        }

    @Test
    fun `walkListingPages ends as complete when the next link points back to a page already read`() =
        runTest {
            val walked =
                walkListingPages(EventSource.MATRIX, listOf("a"), "p1", 10, { listOf("b") }) { items, url ->
                    ListingPage(items, if (url == "p1") "p2" else "p1")
                }

            walked.items shouldContainExactly listOf("a", "b")
            walked.complete shouldBe true
        }

    @Test
    fun `pageNumber reads the page parameter and defaults to the first page`() {
        "https://x.test/events?page=3&per_page=10".pageNumber() shouldBe 3
        "https://x.test/program/".pageNumber() shouldBe 1
    }

    @Test
    fun `nextPageUrl resolves a relative link against the page URL`() {
        page("$entryUrl?page=2", next = "?page=3").nextPageUrl("a.next") shouldBe "$entryUrl?page=3"
    }

    @Test
    fun `nextPageUrl is null on the last page`() {
        page(entryUrl, "a").nextPageUrl("a.next").shouldBeNull()
    }
}
