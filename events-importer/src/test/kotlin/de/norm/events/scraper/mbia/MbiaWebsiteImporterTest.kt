package de.norm.events.scraper.mbia

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
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

/** Unit tests for [MbiaWebsiteImporter]. */
class MbiaWebsiteImporterTest {
    private lateinit var importer: MbiaWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.m-bia.de/veranstaltungen"

    private fun fixture() =
        javaClass.classLoader
            .getResourceAsStream("scraper/mbia/mbia-veranstaltungen.html")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = MbiaWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture(), sourceUrl),
                etag = "\"wix-mbia\"",
                lastModified = "Thu, 29 Oct 2026 10:00:00 GMT"
            )
    }

    @Test
    fun `importEvents reads every night from one page and keeps its validators`() =
        runTest {
            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 9
            result.complete shouldBe true
            result.etag shouldBe "\"wix-mbia\""
            result.lastModified shouldBe "Thu, 29 Oct 2026 10:00:00 GMT"
            coVerify(exactly = 1) { htmlFetcher.fetch(any(), any(), any(), any()) }
        }

    @Test
    fun `eventSource is M_BIA`() {
        importer.eventSource shouldBe EventSource.M_BIA
    }
}
