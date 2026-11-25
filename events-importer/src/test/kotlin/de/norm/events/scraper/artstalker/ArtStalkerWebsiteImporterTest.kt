package de.norm.events.scraper.artstalker

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.io.IOException
import java.math.BigDecimal

/** Unit tests for [ArtStalkerWebsiteImporter]: the venue's listing pages, then one event page per card. */
class ArtStalkerWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = ArtStalkerWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://www.reservix.de/berlin/venue/art-stalker/v41370"
    private val boysUrl =
        "https://www.reservix.de/tickets-the-boys-of-summer-grossstadtmusik-aus-berlin-in-berlin-art-stalker-am-12-12-2026/e2567674"

    private fun stubListing() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = fixtureDocument("artstalker/artstalker-venue-1.html", sourceUrl),
                etag = "\"rx-etag\"",
                lastModified = "Thu, 01 Oct 2026 08:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(any()) } throws IOException("refused")
        stubFetcher(
            "$sourceUrl/2" to "artstalker/artstalker-venue-2.html",
            boysUrl to "artstalker/artstalker-detail-boys-of-summer.html",
            fetcher = htmlFetcher
        )
        // Page 3 is served as page 6, the last, so its disabled link to page 7 must end the walk.
        coEvery { htmlFetcher.fetchDocument("$sourceUrl/3") } returns fixtureDocument("artstalker/artstalker-venue-6.html", "$sourceUrl/3")
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ART_STALKER
    }

    @Test
    fun `walks the listing to its last page and merges the event page over its card`() =
        runTest {
            stubListing()
            val result = importer.importEvents(sourceUrl, null, null)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 22
            result.etag shouldBe "\"rx-etag\""
            result.lastModified shouldBe "Thu, 01 Oct 2026 08:00:00 GMT"
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(match { it.endsWith("/7") || it.endsWith("/4") }) }
            val boys = result.events.single { it.sourceId == "art_stalker:2567674" }
            boys.pricePresale shouldBe BigDecimal("15")
            boys.priceBoxOffice shouldBe BigDecimal("17")
            boys.eventType shouldBe "CONCERT"
            boys.artists.map { it.name } shouldBe listOf("The Boys of Summer")
        }

    @Test
    fun `keeps the card when its event page fails`() =
        runTest {
            stubListing()
            val result = importer.importEvents(sourceUrl, null, null)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.single { it.sourceId == "art_stalker:2545088" }.title shouldBe "Lord Zeppelin – a tribute to Led Zeppelin"
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(sourceUrl, "\"rx-etag\"", null).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `an empty venue page yields no events`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(Jsoup.parse("<html><body></body></html>", sourceUrl), null, null)
            val result = importer.importEvents(sourceUrl, null, null)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
