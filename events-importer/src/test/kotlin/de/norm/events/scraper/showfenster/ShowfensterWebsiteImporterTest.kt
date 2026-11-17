package de.norm.events.scraper.showfenster

import de.norm.events.common.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureText
import de.norm.events.scraper.isForChildren
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

class ShowfensterWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.showfenster-show.de/%C3%BCbersichtskalender"
    private val importer = ShowfensterWebsiteImporter(htmlFetcher, Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), BERLIN))

    private fun serve(html: String) {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = Jsoup.parse(html, sourceUrl), etag = "\"e1\"", lastModified = null)
        coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse(NO_PLAYERS_PAGE, firstArg<String>()) }
    }

    @Test
    fun `imports the fixture and passes the validator on`() =
        runTest {
            serve(fixtureText("showfenster/showfenster-overview.html"))

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 53
            result.events.filterNot { it.isForChildren } shouldHaveSize 46
            result.etag shouldBe "\"e1\""
        }

    @Test
    fun `reads the pages of upcoming concerts only`() =
        runTest {
            val overview = fixtureText("showfenster/showfenster-overview.html")
            serve(overview)
            val listed = ShowfensterOverviewPageScraper().scrape(Jsoup.parse(overview, sourceUrl), sourceUrl)
            val (upcoming, other) = listed.partition { it.eventType == "CONCERT" && !it.eventDate.isBefore(LocalDate.of(2026, 10, 15)) }

            importer.importEvents(sourceUrl)

            upcoming.shouldNotBeEmpty()
            other.filter { it.eventType == "CONCERT" }.shouldNotBeEmpty()
            upcoming.forEach { event -> coVerify(exactly = 1) { htmlFetcher.fetchDocument(event.sourceUrl) } }
            coVerify(exactly = upcoming.size) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `bills the players a concert's page lists`() =
        runTest {
            serve(fixtureText("showfenster/showfenster-overview.html"))
            val nadiaLafi = "https://www.showfenster-show.de/event-details/nadia-lafi-konzert"
            coEvery { htmlFetcher.fetchDocument(nadiaLafi) } returns
                Jsoup.parse(fixtureText("showfenster/showfenster-event-a-life-in-colour.html"), nadiaLafi)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events
                .single { it.sourceUrl == nadiaLafi }
                .artists
                .map { it.name } shouldBe
                listOf("Jonathan Bratoëff", "Richard Maegraith", "Marcel Krömker")
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `a page without the Wix payload imports nothing`() =
        runTest {
            serve("<html><body><div id=\"calendar\"></div></body></html>")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource is SHOWFENSTER`() {
        importer.eventSource shouldBe EventSource.SHOWFENSTER
    }

    private companion object {
        const val NO_PLAYERS_PAGE =
            """<html><head><script type="application/json" id="wix-warmup-data">""" +
                """{"appsWarmupData":{"140603ad-af8d-84a5-2c80-a0f60cb47351":{"EventsPageInitialState":{"event":{"event":""" +
                """{"id":"e1","title":"Konzert","longDescription":{"nodes":[]}}}}}}}</script></head><body></body></html>"""
    }
}
