package de.norm.events.scraper.passionskirche

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedField
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PassionskircheWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val importer = PassionskircheWebsiteImporter(htmlFetcher, clock)
    private val url = "https://www.halle-luja.berlin/kommende-events"
    private val site = "https://www.halle-luja.berlin"

    @Test
    fun `lets the event page own the date and times and flags the pages that fail`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any()) } returns
                FetchResult.Success(document = document("passionskirche-overview.html", url), etag = "\"p1\"", lastModified = null)
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, site)
            for (slug in listOf("wintertales-from-around-the-world", "everytime-we-touch-tour-2026", "the-dublin-legends")) {
                coEvery { htmlFetcher.fetchDocument("$site/$slug") } returns document("passionskirche-event-$slug.html", "$site/$slug")
            }

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"p1\""
            result.events shouldHaveSize 11
            result.events.count { it.detailUnavailable } shouldBe 8
            val wintertales = result.events.single { it.sourceId == "passionskirche:wintertales-from-around-the-world" }
            wintertales.eventDate shouldBe LocalDate.of(2026, 12, 20)
            wintertales.startTime shouldBe LocalTime.of(18, 0)
            result.events.single { it.sourceId == "passionskirche:everytime-we-touch-tour-2026" }.startTime shouldBe LocalTime.of(18, 0)
            val dublin = result.events.single { it.sourceId == "passionskirche:the-dublin-legends" }
            dublin.doorsTime shouldBe LocalTime.of(19, 0)
            dublin.startTime.shouldBeNull()
            val degraded = result.events.single { it.sourceId == "passionskirche:the-high-kings" }
            degraded.eventDate shouldBe LocalDate.of(2026, 11, 16)
            degraded.startTime shouldBe LocalTime.of(20, 0)
            degraded.detailPageOwns shouldContain ScrapedField.RUN_DATES
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `handles an empty listing`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", url), etag = null, lastModified = null)

            (importer.importEvents(url) as ImportResult.Success).events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.PASSIONSKIRCHE
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe true
    }

    private fun document(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/passionskirche/$name")!!
            .bufferedReader()
            .readText(),
        url
    )
}
