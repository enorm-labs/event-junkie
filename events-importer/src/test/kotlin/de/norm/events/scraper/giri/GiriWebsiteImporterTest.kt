package de.norm.events.scraper.giri

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
import java.time.LocalDate

/** Unit tests for [GiriWebsiteImporter]. */
class GiriWebsiteImporterTest {
    private lateinit var importer: GiriWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://giri.berlin/programme/"
    private val decemberUrl = "https://giri.berlin/programme/?month=2026-12"
    private val januaryUrl = "https://giri.berlin/programme/?month=2027-01"

    /** A month page that lists no event but still links the next month. */
    private fun emptyMonthLinking(next: String) =
        Jsoup.parse("""<a class="circle-button" id="month-next" href="$next" aria-label="Next month">→</a>""", sourceUrl)

    private fun document(
        fixture: String,
        url: String
    ): Document {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/giri/$fixture")!!
                .bufferedReader()
                .readText()
        return Jsoup.parse(html, url)
    }

    @BeforeEach
    fun setUp() {
        importer = GiriWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(
                document = document("giri-overview.html", sourceUrl),
                etag = "\"giri-1\"",
                lastModified = "Fri, 16 Oct 2026 18:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(decemberUrl) } returns document("giri-month-2026-12.html", decemberUrl)
    }

    @Test
    fun `importEvents follows month-next to the first month without an event`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 2
            result.events.last().eventDate shouldBe LocalDate.of(2026, 11, 25)
            result.complete shouldBe true
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(decemberUrl) }
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `a later month with no event stops the walk even when it links the next`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(decemberUrl) } returns emptyMonthLinking(januaryUrl)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 2
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(januaryUrl) }
        }

    @Test
    fun `an empty entry month still walks on to the next`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
                FetchResult.Success(document = emptyMonthLinking(januaryUrl), etag = null, lastModified = null)
            coEvery { htmlFetcher.fetchDocument(januaryUrl) } returns document("giri-month-2026-12.html", januaryUrl)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
            result.complete shouldBe true
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(januaryUrl) }
        }

    @Test
    fun `a failed later month keeps the months read and marks the run incomplete`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(decemberUrl) } throws HttpFetchException(503, decemberUrl)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 2
            result.complete shouldBe false
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"giri-1\""
            result.lastModified shouldBe "Fri, 16 Oct 2026 18:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `the later months make the run fetch beyond the entry page`() {
        importer.fetchesBeyondEntryPage shouldBe true
    }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.GIRI
    }
}
