package de.norm.events.scraper.renaissancetheater

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

class RenaissanceTheaterWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = RenaissanceTheaterWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://renaissance-theater.de/spielplan/"
    private val fischen = "https://renaissance-theater.de/produktion/von-fischen-und-frauen/"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/renaissancetheater/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(
                document = loadDocument("renaissancetheater-spielplan.html", sourceUrl),
                etag = "\"rt\"",
                lastModified = "Sat, 17 Oct 2026 04:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(match { it.startsWith("https://renaissance-theater.de/produktion/") }) } answers
            { loadDocument("renaissancetheater-von-fischen-und-frauen.html", firstArg()) }
    }

    @Test
    fun `reads each production page once and applies it to its performances`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"rt\""
            result.lastModified shouldBe "Sat, 17 Oct 2026 04:00:00 GMT"
            val play = result.events.first { it.sourceUrl == fischen }
            play.description.shouldNotBeNull() shouldStartWith "Ein Idyll"
            play.imageUrl shouldBe "https://renaissance-theater.de/wp-content/uploads/Website_Hero-Bild_FuF-1-scaled.jpg"
            result.events
                .map { it.sourceUrl }
                .distinct()
                .forEach { url -> coVerify(exactly = 1) { htmlFetcher.fetchDocument(url) } }
        }

    @Test
    fun `keeps the listing's fields when a production page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(fischen) } throws HttpFetchException(500, fischen)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val play = result.events.first { it.sourceUrl == fischen }
            play.description.shouldBeNull()
            play.detailUnavailable shouldBe true
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
    fun `returns NotModified when the Spielplan is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.RENAISSANCE_THEATER
    }
}
