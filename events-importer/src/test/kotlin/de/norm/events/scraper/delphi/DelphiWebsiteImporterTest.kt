package de.norm.events.scraper.delphi

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [DelphiWebsiteImporter].
 *
 * Only three production pages are stubbed; every other production fetch throws, exercising the
 * degrade-to-programme-row fallback.
 */
class DelphiWebsiteImporterTest {
    private lateinit var importer: DelphiWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://theater-im-delphi.de/programm/"

    private fun productionUrl(id: String) = "https://theater-im-delphi.de/programm/?prod=$id"

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/delphi/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = DelphiWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture("delphi-programm.html"), sourceUrl),
                etag = "\"delphi-etag\"",
                lastModified = "Mon, 03 Aug 2026 09:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        listOf("488", "528", "519").forEach { id ->
            coEvery { htmlFetcher.fetchDocument(productionUrl(id)) } returns
                Jsoup.parse(readFixture("delphi-production-$id.html"), productionUrl(id))
        }
    }

    @Test
    fun `importEvents returns one event per performance`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 24
        }

    @Test
    fun `fetches each production page once, not once per date`() =
        runTest {
            importer.importEvents(sourceUrl)
            // The ballet owns 8 of the 24 rows and must still be fetched exactly once.
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(productionUrl("488")) }
            coVerify(exactly = 14) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"delphi-etag\""
            result.lastModified shouldBe "Mon, 03 Aug 2026 09:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns empty list for a page without a programme`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><section class='events_section'></section></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `applies one production page to every date of its run`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val ballet = result.events.filter { it.sourceUrl == productionUrl("488") }

            ballet shouldHaveSize 8
            ballet.forEach { it.description!! shouldContain "Live-Streichquartett" }
            // Each date keeps its own clock, price and identity.
            val matinee = ballet.first { it.sourceId == "theater_im_delphi:488/2026-09-27-15:00" }
            matinee.eventDate shouldBe LocalDate.of(2026, 9, 27)
            matinee.startTime shouldBe LocalTime.of(15, 0)
            matinee.pricePresale shouldBe BigDecimal("29.95")
            matinee.eventType shouldBe EventType.SHOW.name
        }

    @Test
    fun `falls back to the programme row when a production page cannot be fetched`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val stokes = result.events.first { it.sourceId == "theater_im_delphi:525/2026-11-14-19:30" }

            // The row alone still carries a usable event, teaser and thumbnail included.
            stokes.title shouldBe "Genevieve Stokes"
            stokes.eventDate shouldBe LocalDate.of(2026, 11, 14)
            stokes.startTime shouldBe LocalTime.of(19, 30)
            stokes.description!!.isNotBlank() shouldBe true
            stokes.imageUrl!!.isNotBlank() shouldBe true
            stokes.pricePresale shouldBe BigDecimal("29")
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.THEATER_IM_DELPHI
    }
}
