package de.norm.events.scraper.jonnyknuppel

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class JonnyKnuppelWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://jonnyknueppel.de/"
    private val importer = JonnyKnuppelWebsiteImporter(htmlFetcher)

    private fun serve(html: String) {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = Jsoup.parse(html, sourceUrl), etag = "\"15940\"", lastModified = "Mon, 05 Oct 2026 12:08:35 GMT")
    }

    @Test
    fun `imports the fixture and passes the validators on`() =
        runTest {
            serve(
                javaClass.classLoader
                    .getResourceAsStream("scraper/jonnyknuppel/jonnyknuppel-overview.html")!!
                    .bufferedReader()
                    .readText()
            )

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 28
            result.etag shouldBe "\"15940\""
            result.lastModified shouldBe "Mon, 05 Oct 2026 12:08:35 GMT"
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `an empty calendar imports nothing`() =
        runTest {
            serve("""<html><body><ul class="event-list" data-kalender-list="upcoming"></ul></body></html>""")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource is JONNY_KNUPPEL and the page is the whole programme`() {
        importer.eventSource shouldBe EventSource.JONNY_KNUPPEL
        importer.listsWholeProgramme shouldBe true
    }
}
