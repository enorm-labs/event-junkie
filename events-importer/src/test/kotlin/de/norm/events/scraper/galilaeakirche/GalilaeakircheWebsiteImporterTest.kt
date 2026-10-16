package de.norm.events.scraper.galilaeakirche

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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class GalilaeakircheWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = GalilaeakircheWebsiteImporter(htmlFetcher)
    private val url = "https://galilaea-kirche.de/programm/"
    private val zeaUrl = "https://galilaea-kirche.de/event/zea/"

    @Test
    fun `merges each event page into its listing row and flags the ones that fail`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any()) } returns
                FetchResult.Success(document = document("galilaeakirche-overview.html", url), etag = "\"g1\"", lastModified = null)
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, "https://galilaea-kirche.de/")
            coEvery { htmlFetcher.fetchDocument(zeaUrl) } returns document("galilaeakirche-event-zea.html", zeaUrl)

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"g1\""
            result.events shouldHaveSize 10
            result.events.count { it.detailUnavailable } shouldBe 9
            val zea = result.events.single { it.sourceUrl == zeaUrl }
            zea.eventDate shouldBe LocalDate.of(2026, 10, 16)
            zea.startTime shouldBe LocalTime.of(20, 0)
            zea.doorsTime shouldBe LocalTime.of(19, 30)
            zea.priceBoxOffice shouldBe BigDecimal("20")
            zea.imageUrl!! shouldContain "ZE4-live-op-Explore"
            zea.descriptionAlt.shouldBeNull()
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
        importer.eventSource shouldBe EventSource.GALILAEAKIRCHE
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe true
    }

    private fun document(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/galilaeakirche/$name")!!
            .bufferedReader()
            .readText(),
        url
    )
}
