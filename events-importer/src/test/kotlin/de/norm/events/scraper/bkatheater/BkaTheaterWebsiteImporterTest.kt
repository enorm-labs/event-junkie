package de.norm.events.scraper.bkatheater

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalTime

class BkaTheaterWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = BkaTheaterWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://www.bka-theater.de/spielplan/"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/bkatheater/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(document = loadDocument("bkatheater-spielplan.html", sourceUrl), etag = "\"bka\"", lastModified = null)
        coEvery { htmlFetcher.fetchDocument(match { it.startsWith("https://www.bka-theater.de/prg/") }) } answers
            { loadDocument("bkatheater-production.html", firstArg()) }
    }

    @Test
    fun `reads each production page once and applies it to its performances`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"bka\""
            val show = result.events.first { it.sourceId == "bka_theater:4218" }
            val description = show.description.shouldNotBeNull()
            description shouldStartWith "Satire darf alles. Außer sterben!\n»Wenn die Satire stirbt"
            description shouldNotContain "mehr lesen"
            description shouldNotContain "instagram.com"
            show.doorsTime shouldBe LocalTime.of(19, 0)
            result.events
                .map { it.sourceUrl }
                .distinct()
                .forEach { url -> coVerify(exactly = 1) { htmlFetcher.fetchDocument(url) } }
        }

    @Test
    fun `keeps the listing's fields when a production page fails`() =
        runTest {
            val page = "https://www.bka-theater.de/prg/462"
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
    fun `returns NotModified when the Spielplan is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.BKA_THEATER
    }
}
