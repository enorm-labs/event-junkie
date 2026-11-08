package de.norm.events.scraper.hau

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class HauWebsiteImportersTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val url = "https://www.hebbel-am-ufer.de/en/programme/schedule-tickets/"
    private val november = "${url}prodMonth/11-2026/?cHash=0edaab90db95b38a3c08b5de92f382d9"
    private val december = "${url}prodMonth/12-2026/?cHash=dbc2d543d677d183640de7b2f34bbefe"

    @Test
    fun `walks the published months and enriches each production once`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
                FetchResult.Success(document = fixtureDocument("hau/hau-schedule.html", url), etag = "\"v1\"", lastModified = null)
            stubFetcher(
                november to "hau/hau-schedule-2026-11.html",
                december to "hau/hau-schedule-2026-12.html",
                fetcher = htmlFetcher
            )
            coEvery { htmlFetcher.fetchDocument(match { "/pdetail/" in it }) } answers {
                fixtureDocument("hau/hau-production-soapskin.html", firstArg())
            }

            val result = Hau1WebsiteImporter(htmlFetcher).importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.complete shouldBe true
            result.events shouldHaveSize 11 + 6 + 4
            result.events.all { it.pricePresale == BigDecimal("42") } shouldBe true
            coVerify(exactly = 1) { htmlFetcher.fetchDocument("https://www.hebbel-am-ufer.de/en/programme/pdetail/she-she-pop-alles-moegliche/") }
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.NotModified

            Hau2WebsiteImporter(htmlFetcher).importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for an empty schedule`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", url), etag = null, lastModified = null)

            val result = Hau3WebsiteImporter(htmlFetcher).importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldBe emptyList()
        }

    @Test
    fun `each importer names its own source and reads beyond the entry page`() {
        Hau1WebsiteImporter(htmlFetcher).eventSource shouldBe EventSource.HAU1
        Hau2WebsiteImporter(htmlFetcher).eventSource shouldBe EventSource.HAU2
        Hau3WebsiteImporter(htmlFetcher).eventSource shouldBe EventSource.HAU3
        Hau1WebsiteImporter(htmlFetcher).fetchesBeyondEntryPage shouldBe true
    }
}
