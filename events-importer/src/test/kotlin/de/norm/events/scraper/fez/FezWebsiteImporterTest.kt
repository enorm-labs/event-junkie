package de.norm.events.scraper.fez

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FezWebsiteImporterTest {
    private lateinit var importer: FezWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://fez-berlin.de/orte/astrid-lindgren-buehne/spielplan"

    // The links page 1 prints, cHash and all; page 2's own link to page 3 is answered with a page that links nothing.
    private val page2Url =
        "$sourceUrl?tx_brcalendar2_calendar%5Bcontroller%5D=Date&tx_brcalendar2_calendar%5BcurPage%5D=2&cHash=0cb67e6a41833b5a1651c26c98bf8a29"
    private val lastPage = Jsoup.parse("<html><body><div class='calendar-teaserlist'></div></body></html>", sourceUrl)

    @BeforeEach
    fun setUp() {
        importer = FezWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = fixtureDocument("fez/fez-spielplan.html", sourceUrl),
                etag = "\"fez1\"",
                lastModified = null
            )
        coEvery { htmlFetcher.fetchDocument(page2Url) } returns fixtureDocument("fez/fez-spielplan-page2.html", page2Url)
        coEvery { htmlFetcher.fetchDocument(match { "curPage%5D=3" in it }) } returns lastPage
    }

    @Test
    fun `importEvents follows the page links the schedule prints`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 100
            result.etag shouldBe "\"fez1\""
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(page2Url) }
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(match { "curPage%5D=3" in it }) }
        }

    @Test
    fun `importEvents keeps the first page when a later page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(page2Url) } throws HttpFetchException(503, page2Url)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 50
        }

    @Test
    fun `importEvents returns no events for a page without performances`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = lastPage, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.FEZ
    }
}
