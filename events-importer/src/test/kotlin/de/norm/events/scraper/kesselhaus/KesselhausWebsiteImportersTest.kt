package de.norm.events.scraper.kesselhaus

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class KesselhausWebsiteImportersTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val url = "https://www.kesselhaus.net/de/calendar"

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.NotModified

            KesselhausWebsiteImporter(htmlFetcher).importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for an empty calendar`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", url), etag = null, lastModified = null)

            val result = MaschinenhausWebsiteImporter(htmlFetcher).importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldBe emptyList()
        }

    @Test
    fun `each importer names its own source and reads beyond the entry page`() {
        val kesselhaus = KesselhausWebsiteImporter(htmlFetcher)
        val maschinenhaus = MaschinenhausWebsiteImporter(htmlFetcher)

        kesselhaus.eventSource shouldBe EventSource.KESSELHAUS
        maschinenhaus.eventSource shouldBe EventSource.MASCHINENHAUS
        kesselhaus.listsWholeProgramme shouldBe true
        kesselhaus.fetchesBeyondEntryPage shouldBe true
    }
}
