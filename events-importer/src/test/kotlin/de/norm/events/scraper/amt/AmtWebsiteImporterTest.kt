package de.norm.events.scraper.amt

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AmtWebsiteImporter].
 *
 * Mocks [HtmlFetcher] so the whole multi-page fetch (entry page → month pages) runs offline against
 * the saved fixtures.
 */
class AmtWebsiteImporterTest {
    private lateinit var importer: AmtWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private val entryUrl = "https://www.club-amt.berlin/events"
    private val juneUrl = "https://www.club-amt.berlin/month/june"
    private val julyUrl = "https://www.club-amt.berlin/month/july"

    private fun fixture(
        name: String,
        baseUrl: String
    ): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/amt/$name")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    @BeforeEach
    fun setUp() {
        importer = AmtWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetchDocument(entryUrl) } returns fixture("amt-overview.html", entryUrl)
        coEvery { htmlFetcher.fetchDocument(juneUrl) } returns fixture("amt-month-june.html", juneUrl)
        coEvery { htmlFetcher.fetchDocument(julyUrl) } returns fixture("amt-month-july.html", julyUrl)
        coEvery { htmlFetcher.fetchDocument(match { "/event/" in it }) } answers { fixture("amt-event-brace-brace.html", firstArg()) }
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.AMT
    }

    @Test
    fun `importEvents follows the entry page to every month page and merges the results`() =
        runTest {
            val result = importer.importEvents(entryUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // 6 June nights + 5 July nights.
            result.events shouldHaveSize 11
            result.complete shouldBe true
            result.events.all { it.eventType == EventType.PARTY.name } shouldBe true
            // Multi-page importer intentionally disables conditional caching.
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents adds each night's event page prose after its DJ line`() =
        runTest {
            val events = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events
            val prose = "Brace Brace Berlin organizes genuine techno parties for a friendly crowd in diverse locations."

            events.all { prose in it.description.orEmpty() } shouldBe true
            val substation = events.single { it.sourceId == "amt:substation-3" }
            substation.description.orEmpty().substringBefore("\n\n") shouldContain "Berenice Britney Speed"
            substation.description.orEmpty().substringAfter("\n\n") shouldStartWith prose
        }

    @Test
    fun `importEvents keeps the month page row, flagged, when its event page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(match { "/event/" in it }) } throws HttpFetchException(503, "event page")

            val events = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events

            events shouldHaveSize 11
            events.all { it.detailUnavailable } shouldBe true
        }

    @Test
    fun `importEvents returns an empty success when the entry page links no month pages`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns
                Jsoup.parse("<html><body><p>No programme yet</p></body></html>", entryUrl)

            val result = importer.importEvents(entryUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `importEvents keeps the other months when one fails, and reports the run incomplete`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(julyUrl) } throws HttpFetchException(503, julyUrl)

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 6
            result.complete shouldBe false
        }
}
