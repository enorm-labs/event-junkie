package de.norm.events.scraper

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
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

/** Pins [AbstractMonthPagesWebsiteImporter]'s orchestration on synthetic pages, before #4643 moves it. */
class AbstractMonthPagesWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val entryUrl = "https://venue.example/programm/"

    /** The entry page: a `<base href>` and one `a.month` per month path. */
    private fun entry(vararg months: String): Document =
        Jsoup.parse(
            "<head><base href='https://venue.example/archiv/'></head><body>${months.joinToString("") { "<a class='month' href='$it'></a>" }}</body>",
            entryUrl
        )

    private fun month(
        url: String,
        vararg ids: String
    ): Document = Jsoup.parse("<ul>${ids.joinToString("") { "<li id='$it'></li>" }}</ul>", url)

    private val scrape: (Document, String) -> List<ScrapedEvent> = { document, _ ->
        document.select("li").map {
            ScrapedEvent(title = it.id(), eventDate = LocalDate.of(2026, 10, 1), sourceUrl = "https://venue.example/e/${it.id()}", sourceId = it.id())
        }
    }

    private open inner class Months : AbstractMonthPagesWebsiteImporter(htmlFetcher, "Months", "a.month", scrape) {
        override val eventSource: EventSource = EventSource.ROSA
    }

    private inner class WithEventPages : Months() {
        override val enrichFromEventPage: ((ScrapedEvent, Document) -> ScrapedEvent?) = { event, page ->
            page.selectFirst("p")?.let { event.copy(description = it.text()) }
        }
        override val eventPageOwns: Set<ScrapedField> = setOf(ScrapedField.IMAGE)

        override fun hasEventPage(event: ScrapedEvent): Boolean = event.sourceId != "listed-only"
    }

    private inner class Folding : Months() {
        override fun foldRuns(events: List<ScrapedEvent>): List<ScrapedEvent> = events.take(1)
    }

    private fun stubMonth(
        path: String,
        vararg ids: String
    ) {
        val url = "https://venue.example/archiv/$path"
        coEvery { htmlFetcher.fetchDocument(url) } returns month(url, *ids)
    }

    @Test
    fun `reads every linked month against the base href, without validators, and keeps an event once across months`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns entry("2026-10", "2026-11")
            stubMonth("2026-10", "a", "b")
            stubMonth("2026-11", "b", "c")

            val result = Months().importEvents(entryUrl, etag = "\"ignored\"").shouldBeInstanceOf<ImportResult.Success>()

            result.events.map { it.sourceId } shouldContainExactly listOf("a", "b", "c")
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
            result.complete shouldBe true
            Months().fetchesBeyondEntryPage shouldBe true
            coVerify(exactly = 0) { htmlFetcher.fetch(any(), any(), any(), any()) }
        }

    @Test
    fun `keeps the other months when one fails, and reports the run incomplete`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns entry("2026-10", "2026-11")
            coEvery { htmlFetcher.fetchDocument("https://venue.example/archiv/2026-10") } throws
                HttpFetchException(503, "https://venue.example/archiv/2026-10")
            stubMonth("2026-11", "c")

            val result = Months().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.map { it.sourceId } shouldContainExactly listOf("c")
            result.complete shouldBe false
        }

    @Test
    fun `reads the event pages hasEventPage accepts, and flags a row whose page fails with the fields it owns`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns entry("2026-10")
            stubMonth("2026-10", "a", "fails", "listed-only")
            coEvery { htmlFetcher.fetchDocument("https://venue.example/e/a") } returns Jsoup.parse("<p>Text</p>")
            coEvery { htmlFetcher.fetchDocument("https://venue.example/e/fails") } throws HttpFetchException(503, "https://venue.example/e/fails")

            val events = WithEventPages().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events

            events.single { it.sourceId == "a" }.description shouldBe "Text"
            events.single { it.sourceId == "fails" }.let {
                it.detailUnavailable shouldBe true
                it.detailPageOwns shouldBe setOf(ScrapedField.IMAGE)
            }
            events.single { it.sourceId == "listed-only" }.detailUnavailable shouldBe false
            coVerify(exactly = 0) { htmlFetcher.fetchDocument("https://venue.example/e/listed-only") }
        }

    @Test
    fun `folds the rows last`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns entry("2026-10")
            stubMonth("2026-10", "a", "b")

            Folding()
                .importEvents(entryUrl)
                .shouldBeInstanceOf<ImportResult.Success>()
                .events
                .map { it.sourceId } shouldContainExactly listOf("a")
        }
}
