package de.norm.events.scraper.ausland

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class AuslandWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = AuslandWebsiteImporter(htmlFetcher)
    private val url = "https://ausland.berlin/program/all"
    private val frictiveUrl = "https://ausland.berlin/event/lorena-izquierdo-mizuki-ishikawa-matthias-koole-frictive-frequencies"
    private val frictiveDeUrl = "https://ausland.berlin/de/event/lorena-izquierdo-mizuki-ishikawa-matthias-koole-frictive-frequencies"

    @Test
    fun `merges each event page into its listing row and flags the ones that fail`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any()) } returns
                FetchResult.Success(document = document("ausland-overview.html", url), etag = "\"a1\"", lastModified = null)
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, "https://ausland.berlin/")
            coEvery { htmlFetcher.fetchDocument(frictiveUrl) } returns document("ausland-event-frictive-frequencies.html", frictiveUrl)
            coEvery { htmlFetcher.fetchDocument(frictiveDeUrl) } returns document("ausland-event-frictive-frequencies-de.html", frictiveDeUrl)

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"a1\""
            result.events shouldHaveSize 11
            result.events.count { it.detailUnavailable } shouldBe 10
            val frictive = result.events.single { it.sourceUrl == frictiveUrl }
            frictive.eventDate shouldBe LocalDate.of(2026, 11, 7)
            frictive.doorsTime shouldBe LocalTime.of(19, 0)
            frictive.startTime shouldBe LocalTime.of(20, 0)
            frictive.artists shouldHaveSize 3
            frictive.descriptionAlt!! shouldContain "Die Konzertreihe Frictive Frequencies"
        }

    @Test
    fun `keeps only the doors when the page states doors at the listing's clock and no start`() =
        runTest {
            val pageUrl = "https://ausland.berlin/event/alexandra-macia-zustand-d-winona-lin-frictive-frequencies"
            val page = """<article class="event"><div class="col50 right"><h2>Alexandra Maciá</h2><p>Doors open 19h</p></div></article>"""
            coEvery { htmlFetcher.fetch(url, any(), any()) } returns
                FetchResult.Success(document = document("ausland-overview.html", url), etag = null, lastModified = null)
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, "https://ausland.berlin/")
            coEvery { htmlFetcher.fetchDocument(pageUrl) } returns Jsoup.parse(page, pageUrl)

            val event = (importer.importEvents(url) as ImportResult.Success).events.single { it.sourceUrl == pageUrl }

            event.doorsTime shouldBe LocalTime.of(19, 0)
            event.startTime.shouldBeNull()
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
        importer.eventSource shouldBe EventSource.AUSLAND
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe true
    }

    private fun document(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/ausland/$name")!!
            .bufferedReader()
            .readText(),
        url
    )
}
