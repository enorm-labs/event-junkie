package de.norm.events.scraper.olympiastadion

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureText
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** Unit tests for [OlympiastadionWebsiteImporter]: one listing fetch, then one event page per card. */
class OlympiastadionWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = OlympiastadionWebsiteImporter(htmlFetcher)
    private val listingUrl = "https://olympiastadion.berlin/termine-und-tickets/"
    private val eventUrl = "https://olympiastadion.berlin/events/"

    private fun stubListing(html: String = fixtureText("olympiastadion/olympiastadion-overview.html")) {
        coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(html, listingUrl),
                etag = "\"os-etag\"",
                lastModified = "Mon, 02 Nov 2026 08:00:00 GMT"
            )
    }

    private fun stubPages() {
        mapOf(
            "deutschland-niederlande" to "olympiastadion-detail-match.html",
            "s25-berlin-2027" to "olympiastadion-detail-run.html",
            "u2-the-greatest-show-on-earth-world-tour" to "olympiastadion-detail-concert.html"
        ).forEach { (slug, file) ->
            stubFetcher("$eventUrl$slug/" to "olympiastadion/$file", fetcher = htmlFetcher)
        }
        val english = "https://olympiastadion.berlin/en/events/u2-the-greatest-show-on-earth-world-tour/"
        stubFetcher(english to "olympiastadion/olympiastadion-detail-concert-en.html", fetcher = htmlFetcher)
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.OLYMPIASTADION
    }

    @Test
    fun `keeps the concert, drops the sport and propagates conditional headers`() =
        runTest {
            stubListing()
            stubPages()

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 1
            val u2 = result.events.single()
            u2.sourceId shouldBe "olympiastadion:u2-the-greatest-show-on-earth-world-tour"
            u2.ticketUrl shouldBe "https://www.livenation.de/u2-tickets-adp7159"
            u2.detailUnavailable shouldBe false
            u2.descriptionAlt!! shouldStartWith "U2’s “The Greatest Show On Earth” world tour is coming to Berlin"
            result.etag shouldBe "\"os-etag\""
            result.lastModified shouldBe "Mon, 02 Nov 2026 08:00:00 GMT"
            coVerify(exactly = 4) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `keeps every card flagged when its page fails`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("boom")

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 3
            result.events.all { it.detailUnavailable } shouldBe true
        }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(listingUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `handles a listing with no event cards`() =
        runTest {
            stubListing("<html><body><p>Aktuell stehen keine weiteren Veranstaltungen an.</p></body></html>")

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
