package de.norm.events.scraper.pfefferbergtheater

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PfefferbergTheaterWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = PfefferbergTheaterWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://pfefferberg-theater.de/spielplan/kalender/"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/pfefferbergtheater/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(document = loadDocument("pfefferbergtheater-calendar.html", sourceUrl), etag = "\"pt\"", lastModified = null)
        coEvery { htmlFetcher.fetchDocument(match { it.startsWith(sourceUrl) }) } answers
            { loadDocument("pfefferbergtheater-production.html", firstArg()) }
    }

    @Test
    fun `reads each production page once and applies it to its dates`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"pt\""
            val fil = result.events.single { it.sourceId == "pfefferberg_theater:29935" }
            fil.description.shouldNotBeNull() shouldStartWith "Ausverkauft!"
            fil.imageUrl shouldBe "https://pfefferberg-theater.de/site/assets/files/29934/fil.800x600n.webp"
            fil.ticketUrl.shouldNotBeNull() shouldStartWith "https://pfefferberg.billeto.net/"
            fil.title shouldBe "Fil mit neuer Show – Winter 26/27"
            fil.soldOut shouldBe true
            result.events
                .map { it.sourceUrl }
                .distinct()
                .forEach { url -> coVerify(exactly = 1) { htmlFetcher.fetchDocument(url) } }
        }

    @Test
    fun `keeps the listing's fields when a production page fails`() =
        runTest {
            val page = "https://pfefferberg-theater.de/spielplan/kalender/barbaren-barbies/"
            coEvery { htmlFetcher.fetchDocument(page) } throws HttpFetchException(500, page)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val show = result.events.first { it.sourceUrl == page }
            show.description.shouldBeNull()
            show.detailUnavailable shouldBe true
        }

    @Test
    fun `returns no events for a page without performances`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `returns NotModified when the calendar is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.PFEFFERBERG_THEATER
    }
}
