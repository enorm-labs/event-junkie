package de.norm.events.scraper.parkaue

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldHaveSize
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

class ParkaueWebsiteImporterTest {
    private lateinit var importer: ParkaueWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private val entryUrl = "https://www.parkaue.de/spielplan/"
    private val month = "https://www.parkaue.de/spielplan/spielplan/"
    private val emptyMonth = Jsoup.parse("<html><body><main></main></body></html>")

    @BeforeEach
    fun setUp() {
        importer = ParkaueWebsiteImporter(htmlFetcher)
        stubFetcher(
            entryUrl to "parkaue/parkaue-spielplan.html",
            "${month}2026-11/" to "parkaue/parkaue-month-2026-11.html",
            "${month}2027-01/" to "parkaue/parkaue-month-2027-01.html",
            fetcher = htmlFetcher
        )
        coEvery { htmlFetcher.fetchDocument("${month}2026-12/") } returns emptyMonth
        coEvery { htmlFetcher.fetchDocument("${month}2027-02/") } returns emptyMonth
        coEvery { htmlFetcher.fetchDocument(match { PERFORMANCE_PAGE.containsMatchIn(it) }) } answers
            { fixtureDocument("parkaue/parkaue-production-pinocchio.html", firstArg()) }
    }

    @Test
    fun `importEvents reads every month the schedule links`() =
        runTest {
            val events = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events

            events shouldHaveSize 36 + 34
            coVerify(exactly = 1) { htmlFetcher.fetchDocument("${month}2027-02/") }
        }

    @Test
    fun `importEvents reads each production page once, whichever month lists its dates`() =
        runTest {
            val events = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events

            events.filter { it.title == "Pinocchio" }.all { it.description.orEmpty().startsWith("Der Tischler") } shouldBe true
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(match { "/pinocchio/" in it }) }
        }

    @Test
    fun `importEvents keeps the other months when one fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument("${month}2027-01/") } throws HttpFetchException(503, "${month}2027-01/")

            val events = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events

            events shouldHaveSize 36
            events.first().sourceUrl shouldStartWith "${month}2026-11/"
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.PARKAUE
    }

    private companion object {
        val PERFORMANCE_PAGE = Regex("""/spielplan/spielplan/\d{4}-\d{2}/[^/]+/\d+/$""")
    }
}
