package de.norm.events.scraper.modus

import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** The sold-out state only the venue's pretix shop shows, read through [ModusWebsiteImporter]. */
class ModusTicketShopTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val overviewUrl = "https://modus-berlin.de/events"
    private val detailUrl = "https://modus-berlin.de/event/211026-ModusComedy"
    private val shopUrl = "https://mb.ticketlodge.de/211026-ModusComedy/"

    private suspend fun importedNight(shopPage: String?): ScrapedEvent {
        stubFetcher(
            overviewUrl to "modus/modus-overview-comedy.html",
            detailUrl to "modus/modus-detail-modus-comedy.html",
            fetcher = htmlFetcher
        )
        if (shopPage == null) {
            coEvery { htmlFetcher.fetchDocument(shopUrl) } throws IllegalStateException("shop down")
        } else {
            stubFetcher(shopUrl to "modus/$shopPage", fetcher = htmlFetcher)
        }
        val result = ModusWebsiteImporter(htmlFetcher).importEvents(overviewUrl, null, null)
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events.single()
    }

    @Test
    fun `marks a night sold out when the shop has every ticket type gone`() =
        runTest {
            importedNight("modus-ticketshop-sold-out.html").soldOut shouldBe true
        }

    @Test
    fun `leaves a night on sale while the shop still sells a ticket type`() =
        runTest {
            importedNight("modus-ticketshop-available.html").soldOut shouldBe false
        }

    @Test
    fun `keeps the night when the shop page fails`() =
        runTest {
            val night = importedNight(null)
            night.title shouldBe "Modus Comedy"
            night.soldOut shouldBe false
        }

    @Test
    fun `reads no sold-out state off a page without ticket types`() {
        ModusTicketShopScraper.soldOut(Jsoup.parse("<html><body><p>Der Vorverkauf beginnt bald.</p></body></html>")) shouldBe false
    }
}
