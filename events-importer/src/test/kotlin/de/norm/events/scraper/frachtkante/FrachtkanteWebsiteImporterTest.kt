package de.norm.events.scraper.frachtkante

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

class FrachtkanteWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://frachtkante.de/de/"
    private val importer = FrachtkanteWebsiteImporter(htmlFetcher)

    private fun serve(html: String) {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = Jsoup.parse(html, sourceUrl), etag = "\"fk-1\"", lastModified = "Tue, 13 Oct 2026 18:00:00 GMT")
    }

    @Test
    fun `imports the fixture and passes the validators on`() =
        runTest {
            serve(
                javaClass.classLoader
                    .getResourceAsStream("scraper/frachtkante/frachtkante-overview.html")!!
                    .bufferedReader()
                    .readText()
            )

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 2
            result.etag shouldBe "\"fk-1\""
            result.lastModified shouldBe "Tue, 13 Oct 2026 18:00:00 GMT"
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `an empty programme imports nothing`() =
        runTest {
            serve("""<html><body><h1>Veranstaltungen</h1></body></html>""")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource is FRACHTKANTE and the page is the whole programme`() {
        importer.eventSource shouldBe EventSource.FRACHTKANTE
        importer.listsWholeProgramme shouldBe true
    }
}
