package de.norm.events.scraper.matrix

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
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

/**
 * Unit tests for [MatrixWebsiteImporter].
 *
 * Mocks [HtmlFetcher] so the whole month walk (entry page → August → … → the empty November page
 * that ends it) runs offline against the saved fixtures.
 */
class MatrixWebsiteImporterTest {
    private lateinit var importer: MatrixWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private val entryUrl = "https://www.matrix-berlin.de/party-in-berlin/"

    private fun monthUrl(month: Int) = "https://www.matrix-berlin.de/party-in-berlin/?get_month=$month&get_year=2026"

    private fun fixture(
        name: String,
        baseUrl: String
    ): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/matrix/$name")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    @BeforeEach
    fun setUp() {
        importer = MatrixWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetchDocument(entryUrl) } returns fixture("matrix-month-july.html", entryUrl)
        coEvery { htmlFetcher.fetchDocument(monthUrl(8)) } returns fixture("matrix-month-august.html", monthUrl(8))
        coEvery { htmlFetcher.fetchDocument(monthUrl(9)) } returns fixture("matrix-month-september.html", monthUrl(9))
        coEvery { htmlFetcher.fetchDocument(monthUrl(10)) } returns fixture("matrix-month-october.html", monthUrl(10))
        coEvery { htmlFetcher.fetchDocument(monthUrl(11)) } returns fixture("matrix-month-november.html", monthUrl(11))
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.MATRIX
    }

    @Test
    fun `importEvents walks the month pages from the entry page and merges the results`() =
        runTest {
            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            // 2 remaining July nights + 31 August + 30 September + 27 October; November is empty.
            result.events shouldHaveSize 90
            result.events.all { it.eventType == EventType.PARTY.name } shouldBe true
            result.events.first().eventDate shouldBe LocalDate.of(2026, 7, 30)
            result.events.last().eventDate shouldBe LocalDate.of(2026, 10, 27)
            // Multi-page importer intentionally disables conditional caching.
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents keeps the entry month when a later month page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(monthUrl(8)) } throws RuntimeException("connection reset")

            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 2
            result.complete shouldBe false
        }

    @Test
    fun `importEvents stops at the first month the venue offers no next link for`() =
        runTest {
            importer.importEvents(entryUrl)

            // The empty November page carries no next-month chevron, so December is never requested.
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(monthUrl(11)) }
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(monthUrl(12)) }
        }

    // The live site keeps the next-month chevron on an empty month, so the walk must end on the marker (#2319).
    @Test
    fun `importEvents stops at the first empty month even when it still links the next one`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(monthUrl(11)) } returns
                fixture("matrix-month-empty-keeps-next-link.html", monthUrl(11))

            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 90
            result.complete shouldBe true
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(match { it.contains("get_year=2027") }) }
        }

    @Test
    fun `importEvents returns the entry page alone when it links no further month`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns
                fixture("matrix-month-november.html", entryUrl)

            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(monthUrl(8)) }
        }

    @Test
    fun `importEvents returns an empty success for a page with no programme markup`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns
                Jsoup.parse("<html><body><p>Baustelle</p></body></html>", entryUrl)

            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }
}
