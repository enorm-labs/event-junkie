package de.norm.events.scraper.distel

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedField
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DistelWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = DistelWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://distel-berlin.de/spielplan/kalender/"

    // The October snapshot links November; February's snapshot stands in for it and links March, which is empty.
    private val november = "$sourceUrl?month=202611"
    private val march = "$sourceUrl?month=202703"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/distel/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = loadDocument("distel-kalender.html", sourceUrl), etag = null, lastModified = null)
        coEvery { htmlFetcher.fetchDocument(november) } returns loadDocument("distel-kalender-202702.html", november)
        coEvery { htmlFetcher.fetchDocument(march) } returns loadDocument("distel-kalender-202703-empty.html", march)
        coEvery { htmlFetcher.fetchDocument(match { "/spielplan/event/" in it }) } answers { loadDocument("distel-show.html", firstArg()) }
    }

    @Test
    fun `walks the months to the first empty one and reads each show page once`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 34
            result.complete shouldBe true
            result.events.all { it.description != null } shouldBe true
            val shows = result.events.map { it.sourceUrl }.distinct()
            shows.forEach { show -> coVerify(exactly = 1) { htmlFetcher.fetchDocument(show) } }
        }

    @Test
    fun `keeps the calendar's fields when a show page fails`() =
        runTest {
            val show = "https://distel-berlin.de/spielplan/event/die-rueckkehr-der-spaeti-ritter/"
            coEvery { htmlFetcher.fetchDocument(show) } throws HttpFetchException(500, show)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.first { it.sourceUrl == show }
            event.description.shouldBeNull()
            event.ticketUrl.shouldNotBeNull()
            event.detailUnavailable shouldBe true
            event.detailPageOwns shouldBe setOf(ScrapedField.IMAGE)
            result.events.filter { it.sourceUrl != show }.forEach { it.detailUnavailable shouldBe false }
        }

    @Test
    fun `returns NotModified when the calendar is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.DISTEL
    }
}
