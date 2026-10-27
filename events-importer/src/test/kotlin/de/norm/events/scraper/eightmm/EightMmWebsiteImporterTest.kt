package de.norm.events.scraper.eightmm

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Unit tests for [EightMmWebsiteImporter]. */
class EightMmWebsiteImporterTest {
    private lateinit var importer: EightMmWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.8mmbar.de/program"
    private val base = "https://www.8mmbar.de/program/2026/10"

    private fun fixture(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/eightmm/$name")!!
                .bufferedReader()
                .readText(),
            sourceUrl
        )

    @BeforeEach
    fun setUp() {
        importer = EightMmWebsiteImporter(htmlFetcher, Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneId.of("Europe/Berlin")))
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(
                document = fixture("eightmm-program.html"),
                etag = "\"8mm\"",
                lastModified = "Sat, 17 Oct 2026 08:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument("$base/17/rmfc-live-in-8mm") } returns fixture("eightmm-event-rmfc.html")
        coEvery { htmlFetcher.fetchDocument(match { it.contains("jeff-clark") }) } returns fixture("eightmm-event-residency.html")
    }

    @Test
    fun `importEvents reads the listing and each event page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 3
            val rmfc = result.events.first { it.sourceUrl.endsWith("rmfc-live-in-8mm") }
            rmfc.startTime shouldBe LocalTime.of(20, 30)
            rmfc.doorsTime shouldBe LocalTime.of(19, 0)
            result.events
                .first { it.sourceUrl.contains("/18/") }
                .description
                .shouldBeNull()
        }

    @Test
    fun `a residency night keeps its DJ through the event page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val residency = result.events.filter { it.sourceUrl.contains("jeff-clark") }
            residency shouldHaveSize 2
            residency.forEach { night ->
                night.artists.map { it.name to it.role } shouldContainExactly listOf("Jeff Clark" to ArtistRole.DJ)
            }
        }

    @Test
    fun `a failed event page keeps the listed event, flagged`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument("$base/17/rmfc-live-in-8mm") } throws IllegalStateException("boom")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val rmfc = result.events.first { it.sourceUrl.endsWith("rmfc-live-in-8mm") }
            rmfc.detailUnavailable shouldBe true
            rmfc.startTime shouldBe LocalTime.of(19, 0)
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"8mm\""
            result.lastModified shouldBe "Sat, 17 Oct 2026 08:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `importEvents returns no events for an empty page`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `the run reads event pages, so it is never skipped on the listing's validators`() {
        importer.fetchesBeyondEntryPage shouldBe true
        importer.eventSource shouldBe EventSource.EIGHT_MM
    }
}
