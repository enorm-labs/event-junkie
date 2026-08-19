package de.norm.events.scraper.panke

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [PankeWebsiteImporter].
 */
class PankeWebsiteImporterTest {
    private lateinit var importer: PankeWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.pankeculture.com/programme/"

    private fun readFixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/panke/panke-programme.html")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = PankeWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture(), sourceUrl),
                etag = "\"panke-etag\"",
                lastModified = "Mon, 03 Aug 2026 09:00:00 GMT"
            )
    }

    @Test
    fun `importEvents returns the venue's upcoming programme`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 9
        }

    @Test
    fun `importEvents needs a single request, there being no detail pages`() =
        runTest {
            importer.importEvents(sourceUrl)
            coVerify(exactly = 1) { htmlFetcher.fetch(sourceUrl, any(), any()) }
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"panke-etag\""
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
            val emptyDoc = Jsoup.parse("<html><body><div class='et_pb_events_0'></div></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `importEvents maps the events it collected`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val eee = result.events.first { it.sourceId == "panke:17227" }

            eee.title shouldBe "EEE"
            eee.eventDate shouldBe LocalDate.of(2026, 8, 8)
            eee.startTime shouldBe LocalTime.of(22, 30)
            eee.eventType shouldBe EventType.PARTY.name
            eee.artists.map { it.name } shouldBe listOf("Ziúr", "bela", "ALEX WANG", "Kilo Vee")
            eee.sourceUrl shouldBe sourceUrl
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.PANKE
    }
}
