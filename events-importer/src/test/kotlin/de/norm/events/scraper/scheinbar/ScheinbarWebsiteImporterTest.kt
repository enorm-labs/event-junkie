package de.norm.events.scraper.scheinbar

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
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
import java.time.LocalDate

class ScheinbarWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = ScheinbarWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://www.scheinbar.de/programm/"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/scheinbar/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = loadDocument("scheinbar-programm.html", sourceUrl), etag = "\"sb\"", lastModified = null)
        coEvery { htmlFetcher.fetchDocument(match { it.startsWith("https://www.scheinbar.de/programm/") }) } answers
            { loadDocument("scheinbar-programm-detail.html", firstArg()) }
    }

    @Test
    fun `reads each programme page once and applies it to its evenings`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 50
            result.etag shouldBe "\"sb\""
            result.events
                .first { it.eventDate == LocalDate.of(2026, 10, 4) }
                .description
                .shouldNotBeNull() shouldStartWith
                "Hoffentlich hält sich die Bräune"
            result.events.first().imageUrl shouldBe "https://www.scheinbar.de/site/assets/files/14016/image.png"
            result.events
                .map { it.sourceUrl }
                .distinct()
                .forEach { url -> coVerify(exactly = 1) { htmlFetcher.fetchDocument(url) } }
        }

    @Test
    fun `keeps the listing's fields when a programme page fails`() =
        runTest {
            val page = "https://www.scheinbar.de/programm/hans-und-goldi/"
            coEvery { htmlFetcher.fetchDocument(page) } throws HttpFetchException(500, page)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events
                .first { it.sourceUrl == page }
                .description
                .shouldBeNull()
        }

    @Test
    fun `returns NotModified when the programme is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.SCHEINBAR
    }
}
