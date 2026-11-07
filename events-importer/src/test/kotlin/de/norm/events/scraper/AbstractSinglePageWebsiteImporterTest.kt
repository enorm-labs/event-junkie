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

    /** Rows whose id starts with `dateless` carry no date; each row's page sets the description from its `<p>`. */
    private val scrapeWithDateless: (Document, String) -> List<ScrapedEvent> = { document, _ ->
        document.select("li").map {
            ScrapedEvent(
                title = it.id(),
                eventDate = if (it.id().startsWith("dateless")) UNRESOLVED_EVENT_DATE else LocalDate.of(2026, 10, 1),
                sourceUrl = "https://venue.example/e/${it.id()}",
                sourceId = it.id(),
                description = "teaser"
            )
        }
    }

    private inner class WithEventPages : AbstractSinglePageWebsiteImporter(htmlFetcher, "With Event Pages", scrapeWithDateless) {
        override val eventSource: EventSource = EventSource.ROSA
        override val enrichFromEventPage: ((ScrapedEvent, Document) -> ScrapedEvent?) = { event, page ->
            page.selectFirst("p")?.let { event.copy(description = it.text(), eventDate = LocalDate.of(2026, 10, 3)) }
        }
        override val eventPageSecondLanguage = SecondLanguagePage("en") { it.selectFirst("p")?.text() }
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

    @Test
    fun `retries a dateless row's event page once, then drops the row and counts it`() =
        runTest {
            stubEntry(page(entryUrl, "a", "dateless"))
            coEvery { htmlFetcher.fetchDocument("https://venue.example/e/a") } returns Jsoup.parse("<p>Text</p>")
            coEvery { htmlFetcher.fetchDocument("https://venue.example/e/dateless") } throws
                HttpFetchException(503, "https://venue.example/e/dateless")

            val result = WithEventPages().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.map { it.sourceId } shouldContainExactly listOf("a")
            result.droppedUnresolvedDate shouldBe 1
            coVerify(exactly = 2) { htmlFetcher.fetchDocument("https://venue.example/e/dateless") }
            coVerify(exactly = 1) { htmlFetcher.fetchDocument("https://venue.example/e/a") }
        }

    @Test
    fun `reads the second language from an event page that changed the text, and not from one that kept the teaser`() =
        runTest {
            stubEntry(page(entryUrl, "a", "same"))
            coEvery { htmlFetcher.fetchDocument("https://venue.example/e/a") } returns
                Jsoup.parse("<head><link rel='alternate' hreflang='en' href='https://venue.example/en/a'></head><p>Deutsch</p>", "https://venue.example/e/a")
            coEvery { htmlFetcher.fetchDocument("https://venue.example/e/same") } returns
                Jsoup.parse(
                    "<head><link rel='alternate' hreflang='en' href='https://venue.example/en/same'></head><p>teaser</p>",
                    "https://venue.example/e/same"
                )
            coEvery { htmlFetcher.fetchDocument("https://venue.example/en/a") } returns Jsoup.parse("<p>English</p>")

            val events = WithEventPages().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events

            events.single { it.sourceId == "a" }.descriptionAlt shouldBe "English"
            events.single { it.sourceId == "same" }.descriptionAlt shouldBe null
            coVerify(exactly = 0) { htmlFetcher.fetchDocument("https://venue.example/en/same") }
        }
}
