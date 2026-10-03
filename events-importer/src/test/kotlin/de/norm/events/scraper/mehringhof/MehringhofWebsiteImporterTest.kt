package de.norm.events.scraper.mehringhof

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MehringhofWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = MehringhofWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://www.mehringhoftheater.de/programm/"
    private val november = "https://www.mehringhoftheater.de/programm/november-2026/"
    private val december = "https://www.mehringhoftheater.de/programm/dezember-2026/"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/mehringhof/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = loadDocument("mehringhof-programm.html", sourceUrl), etag = null, lastModified = null)
        coEvery { htmlFetcher.fetchDocument(november) } returns loadDocument("mehringhof-programm-november.html", november)
        coEvery { htmlFetcher.fetchDocument(december) } returns loadDocument("mehringhof-programm-dezember.html", december)
        coEvery { htmlFetcher.fetchDocument(match { "/produkte/" in it }) } answers { loadDocument("mehringhof-ticket.html", firstArg()) }
    }

    @Test
    fun `walks the three months and keeps the listing's title over the ticket page's`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.complete shouldBe true
            result.events.map { it.eventDate.monthValue }.distinct() shouldBe listOf(10, 11, 12)
            val show = result.events.first { it.sourceId == "mehringhof:94723" }
            show.title shouldBe "Hinnerk Köhn mit NOIR (Berlin Premiere)"
            show.description.shouldNotBeNull()
            show.soldOut shouldBe true
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.MEHRINGHOF
    }
}
