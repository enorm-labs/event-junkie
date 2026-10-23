package de.norm.events.scraper.eighthundreda

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

class EightHundredAWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = EightHundredAWebsiteImporter(htmlFetcher)
    private val url = "https://www.800aberlin.com/"

    @Test
    fun `imports the home page calendar`() =
        runTest {
            val html = javaClass.classLoader.getResourceAsStream("scraper/eighthundreda/800a-home.html")!!
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse(html, null, url), etag = null, lastModified = null)

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 31
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.EIGHT_HUNDRED_A
        importer.listsWholeProgramme shouldBe false
    }
}
