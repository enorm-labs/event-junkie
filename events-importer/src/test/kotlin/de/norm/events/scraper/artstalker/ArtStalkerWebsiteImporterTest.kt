package de.norm.events.scraper.artstalker

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.io.IOException
import java.math.BigDecimal

/** Unit tests for [ArtStalkerWebsiteImporter]: the shop root, then one event page per card. */
class ArtStalkerWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = ArtStalkerWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://art-stalker.reservix.de/"
    private val patlanskyUrl = "https://art-stalker.reservix.de/tickets-dan-patlansky-blues-rock-in-berlin-art-stalker-am-3-10-2026/e2535642"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/artstalker/$name")!!
            .bufferedReader()
            .readText()

    private fun stubListing() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("artstalker-overview.html"), sourceUrl),
                etag = "\"rx-etag\"",
                lastModified = "Thu, 01 Oct 2026 08:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(patlanskyUrl) } returns Jsoup.parse(fixture("artstalker-detail-patlansky.html"), patlanskyUrl)
        coEvery { htmlFetcher.fetchDocument(match { it != patlanskyUrl }) } throws IOException("refused")
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ART_STALKER
    }

    @Test
    fun `merges the event page over its card and keeps the card's type and artists`() =
        runTest {
            stubListing()
            val result = importer.importEvents(sourceUrl, null, null)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 25
            result.etag shouldBe "\"rx-etag\""
            result.lastModified shouldBe "Thu, 01 Oct 2026 08:00:00 GMT"
            val patlansky = result.events.single { it.sourceId == "art_stalker:2535642" }
            patlansky.pricePresale shouldBe BigDecimal("19")
            patlansky.priceBoxOffice shouldBe BigDecimal("23")
            patlansky.eventType shouldBe "CONCERT"
            patlansky.artists.map { it.name } shouldBe listOf("Dan Patlansky")
        }

    @Test
    fun `falls back to the card when its event page fails`() =
        runTest {
            stubListing()
            val result = importer.importEvents(sourceUrl, null, null)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.single { it.sourceId == "art_stalker:2555973" }.pricePresale shouldBe BigDecimal("11.40")
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(sourceUrl, "\"rx-etag\"", null).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `an empty shop page yields no events`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(Jsoup.parse("<html><body></body></html>", sourceUrl), null, null)
            val result = importer.importEvents(sourceUrl, null, null)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
