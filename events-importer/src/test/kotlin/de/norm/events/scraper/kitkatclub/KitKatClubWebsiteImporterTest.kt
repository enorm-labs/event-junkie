package de.norm.events.scraper.kitkatclub

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KitKatClubWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = KitKatClubWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://kitkatclub.org/Home/Club/Index.html"

    @BeforeEach
    fun setUp() {
        val programme = fixtureDocument("kitkatclub/kitkatclub-programme.html", sourceUrl, charset = null)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = programme, etag = "\"abc\"", lastModified = "Sun, 04 Oct 2026 10:00:00 GMT")
    }

    @Test
    fun `imports the week's nights with the page's validators`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 8
            result.etag shouldBe "\"abc\""
            result.lastModified shouldBe "Sun, 04 Oct 2026 10:00:00 GMT"
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.KITKATCLUB
        importer.listsWholeProgramme shouldBe true
    }
}
