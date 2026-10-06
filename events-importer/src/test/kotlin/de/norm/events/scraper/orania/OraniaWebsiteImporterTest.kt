package de.norm.events.scraper.orania

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class OraniaWebsiteImporterTest {
    private lateinit var importer: OraniaWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://orania.berlin/concerts"
    private val page2Url = "$sourceUrl/page/2"
    private val page3Url = "$sourceUrl/page/3"
    private val detailUrl = "https://orania.berlin/oraniaconcerts/event/event/matti-klein-tayfun-schulzke-20261002"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/orania/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        importer = OraniaWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = loadDocument("orania-overview.html", sourceUrl),
                etag = "\"orania1\"",
                lastModified = "Sat, 03 Oct 2026 08:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(page2Url) } returns loadDocument("orania-overview-page-2.html", page2Url)
        coEvery { htmlFetcher.fetchDocument(page3Url) } returns loadDocument("orania-overview-page-last.html", page3Url)
        // One detail fixture stands in for every event page.
        coEvery { htmlFetcher.fetchDocument(match { it.contains("/oraniaconcerts/") }) } answers
            { loadDocument("orania-detail.html", firstArg()) }
    }

    @Test
    fun `reads every listing page and merges each event page into its listing row`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 25
            result.complete shouldBe true
            val first = result.events.first { it.sourceUrl == detailUrl }
            first.eventDate shouldBe LocalDate.of(2026, 10, 2)
            first.startTime shouldBe LocalTime.of(21, 0)
            first.subtitle shouldBe "Pop, Jazz, Grooves & Melodies - Piano meets Percussion"
            first.description.orEmpty() shouldStartWith "The duo of reputed pianist"
            first.imageUrl shouldBe "https://orania.berlin/fileadmin/Concerts/2025_Herbst/MattiKlein_meets_TayfunSchulzke__c_RubenBauer3.jpg"
            first.free shouldBe true
            result.events.last().eventDate shouldBe LocalDate.of(2026, 11, 27)
        }

    @Test
    fun `stores the German event page's biography as the second language`() =
        runTest {
            val germanUrl = "https://orania.berlin/de/konzerte/event/termin/agita-rando-20261014"
            coEvery { htmlFetcher.fetchDocument(match { it.contains("/oraniaconcerts/") }) } answers
                { loadDocument("orania-detail-agita-rando-en.html", firstArg()) }
            coEvery { htmlFetcher.fetchDocument(germanUrl) } returns loadDocument("orania-detail-agita-rando-de.html", germanUrl)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.first()
            event.description.orEmpty() shouldContain "An evening dedicated to one of the great individualists"
            event.descriptionAlt.orEmpty() shouldContain "Ein Abend, der einem der großen Individualisten"
            coVerify(exactly = result.events.size) { htmlFetcher.fetchDocument(germanUrl) }
        }

    @Test
    fun `keeps the English text when the German event page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(match { it.contains("/oraniaconcerts/") }) } answers
                { loadDocument("orania-detail-agita-rando-en.html", firstArg()) }
            coEvery { htmlFetcher.fetchDocument(match { it.contains("/de/konzerte/") }) } throws HttpFetchException(404, "de")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 25
            result.events.forEach {
                it.descriptionAlt.shouldBeNull()
                it.detailUnavailable shouldBe false
            }
        }

    @Test
    fun `keeps the pages already read when a later page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(page2Url) } throws HttpFetchException(503, page2Url)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 10
            result.complete shouldBe false
        }

    @Test
    fun `keeps the listing row when its event page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(detailUrl) } throws HttpFetchException(500, detailUrl)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.first { it.sourceUrl == detailUrl }
            event.detailUnavailable shouldBe true
            event.eventDate shouldBe LocalDate.of(2026, 10, 2)
        }

    @Test
    fun `propagates the entry page's validators`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"orania1\""
            result.lastModified shouldBe "Sat, 03 Oct 2026 08:00:00 GMT"
        }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for a page without a listing`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.ORANIA
    }
}
