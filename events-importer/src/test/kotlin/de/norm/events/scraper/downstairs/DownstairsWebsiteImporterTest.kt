package de.norm.events.scraper.downstairs

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
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
import java.math.BigDecimal

class DownstairsWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = DownstairsWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://www.downstairscomedy.shop/catalog/tickets?limit=100&use_sold=true&use_pagy=true&view_type=list"
    private val page2 = "$sourceUrl&page=2"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/downstairs/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetchTurboFrame(sourceUrl, "tickets") } returns loadDocument("downstairs-tickets-page1.html", sourceUrl)
        coEvery { htmlFetcher.fetchTurboFrame(page2, "tickets") } returns loadDocument("downstairs-tickets-page2.html", page2)
        coEvery { htmlFetcher.fetchDocument(match { "/catalog/tickets/" in it }) } answers { loadDocument("downstairs-ticket.html", firstArg()) }
    }

    @Test
    fun `reads both frame pages and each show's ticket page once`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.complete shouldBe true
            result.events.size shouldBe
                result.events
                    .map { it.sourceId }
                    .distinct()
                    .size
            val allstars = result.events.first()
            allstars.description.shouldNotBeNull() shouldStartWith "DOWNSTAIRS - Der Comedy Club von Felix Lobrecht"
            allstars.imageUrl.shouldNotBeNull()
            allstars.pricePresale shouldBe BigDecimal("25.5")
            val shows =
                result.events
                    .map { it.title }
                    .distinct()
                    .size
            coVerify(exactly = shows) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `keeps the list's fields when a ticket page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(500, "x")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events
                .first()
                .description
                .shouldBeNull()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.DOWNSTAIRS
    }
}
