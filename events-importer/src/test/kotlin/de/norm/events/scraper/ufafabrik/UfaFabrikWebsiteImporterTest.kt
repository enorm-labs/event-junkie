package de.norm.events.scraper.ufafabrik

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.io.IOException

/** Unit tests for [UfaFabrikWebsiteImporter]: this month, the next, then one show page per row. */
class UfaFabrikWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = UfaFabrikWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://ufafabrik.de/spielplan.html"
    private val novemberUrl = "https://ufafabrik.de/program/202611"
    private val elsaUrl = "https://ufafabrik.de/veranstaltung/40154/elsa"
    private val bozkusUrl = "https://ufafabrik.de/veranstaltung/40877/ahmet-bozkus"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/ufafabrik/$name")!!
            .bufferedReader()
            .readText()

    private fun stubPages() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(Jsoup.parse(fixture("ufafabrik-spielplan.html"), sourceUrl), "\"ufa-etag\"", null)
        coEvery { htmlFetcher.fetchDocument(novemberUrl) } returns Jsoup.parse(fixture("ufafabrik-program-202611.html"), novemberUrl)
        coEvery { htmlFetcher.fetchDocument(elsaUrl) } returns Jsoup.parse(fixture("ufafabrik-detail-elsa.html"), elsaUrl)
        coEvery { htmlFetcher.fetchDocument(bozkusUrl) } returns Jsoup.parse(fixture("ufafabrik-detail-ahmet-bozkus.html"), bozkusUrl)
        coEvery { htmlFetcher.fetchDocument(match { it !in setOf(novemberUrl, elsaUrl, bozkusUrl) }) } throws IOException("refused")
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.UFA_FABRIK
    }

    @Test
    fun `reads this month and the next, and stops there`() =
        runTest {
            stubPages()
            val result = importer.importEvents(sourceUrl, null, null)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 59
            result.etag shouldBe "\"ufa-etag\""
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(novemberUrl) }
            coVerify(exactly = 0) { htmlFetcher.fetchDocument("https://ufafabrik.de/program/202612") }
        }

    @Test
    fun `the show page adds the blurb and the month row keeps its own fields`() =
        runTest {
            stubPages()
            val result = importer.importEvents(sourceUrl, null, null)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val elsa = result.events.single { it.sourceId == "ufa_fabrik:40154-2026-10-01-2000" }
            elsa.title shouldBe "ELSA"
            elsa.room shouldBe "Varieté Salon"
            val description = elsa.description.orEmpty()
            description shouldStartWith "Sie ist hungrig."
            description shouldEndWith "drums: Daniel Louis"
            description shouldNotContain "Foto:"
            description shouldNotContain "eingelöst"
            result.events
                .single { it.sourceId == "ufa_fabrik:40173-2026-10-02-2000" }
                .description
                .shouldBeNull()
        }

    @Test
    fun `a Ticketlink in the blurb fills only a row without its own ticket link`() =
        runTest {
            stubPages()
            val result = importer.importEvents(sourceUrl, null, null)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.single { it.sourceUrl == bozkusUrl }.ticketUrl shouldBe "https://www.tickettailor.com/events/dwjmm/2315670"
            result.events.single { it.sourceId == "ufa_fabrik:40154-2026-10-01-2000" }.ticketUrl shouldBe "https://ufafabrik.de/node/40154/booking"
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(sourceUrl, "\"ufa-etag\"", null).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `an empty month page yields no events and no further page`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(Jsoup.parse("<html><body></body></html>", sourceUrl), null, null)
            val result = importer.importEvents(sourceUrl, null, null)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
