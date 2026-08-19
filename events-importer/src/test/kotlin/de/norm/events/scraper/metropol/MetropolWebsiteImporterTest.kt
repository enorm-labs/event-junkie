package de.norm.events.scraper.metropol

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
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
import java.time.LocalTime

/**
 * Unit tests for [MetropolWebsiteImporter].
 *
 * Only the detail pages captured as fixtures are stubbed; every other detail fetch throws,
 * exercising the base class's degrade-to-overview fallback.
 */
class MetropolWebsiteImporterTest {
    private lateinit var importer: MetropolWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://metropol-berlin.de/events"

    /** Detail fixtures keyed by the slug of the page they were captured from. */
    private val detailFixtures =
        mapOf(
            "2026-08-04-thy-art-is-murder" to "thy-art-is-murder",
            "2026-09-05-mucco" to "mucco",
            "2026-09-11-party101" to "party101",
            "2026-10-04-brkn" to "brkn",
            "2026-10-08-shadow-of-intent" to "shadow-of-intent",
            "2026-10-13-loi" to "loi",
            "2026-11-21-frank-martini-party-of-the-century" to "frank-martini-party"
        )

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/metropol/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = MetropolWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture("metropol-overview.html"), sourceUrl),
                etag = "\"abc123\"",
                lastModified = "Sat, 01 Aug 2026 10:00:00 GMT"
            )

        // Unstubbed detail pages fail, so most events fall back to their overview data.
        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        detailFixtures.forEach { (slug, fixture) ->
            val detailUrl = "https://metropol-berlin.de/event/$slug"
            coEvery { htmlFetcher.fetchDocument(detailUrl) } returns
                Jsoup.parse(readFixture("metropol-detail-$fixture.html"), detailUrl)
        }
    }

    @Test
    fun `importEvents extracts all events from fixture`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 41
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"abc123\""
            result.lastModified shouldBe "Sat, 01 Aug 2026 10:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns empty list for a page without an event list`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><div id='em-wrapper'></div></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `merges the detail page over the listing while keeping the listing's support acts`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val thyArt = result.events.first { it.sourceId == "metropol:2026-08-04-thy-art-is-murder" }

            // Detail-only fields.
            thyArt.promoters shouldBe listOf("Trinity Music")
            thyArt.imageUrl shouldBe
                "https://metropol-berlin.de/wp-content/uploads/2026/03/thy-art-is-murder-berlin-500x334-1.webp"
            thyArt.description!!.isNotBlank() shouldBe true
            // Listing-only fields: the detail h1 names the headliner alone.
            thyArt.artists.map { it.name } shouldBe
                listOf("Thy Art is Murder", "Fit For An Autopsy", "Sun Eater", "Protest The Hero")
            thyArt.subtitle shouldBe "Fit For An Autopsy + Sun Eater + Protest The Hero"
        }

    @Test
    fun `falls back to listing data when a detail page cannot be fetched`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // Khamari has no detail fixture, so its detail fetch throws.
            val khamari = result.events.first { it.sourceId == "metropol:2026-09-06-khamari" }
            khamari.title shouldBe "Khamari"
            khamari.startTime shouldBe LocalTime.of(20, 0)
            khamari.doorsTime shouldBe LocalTime.of(19, 0)
            khamari.artists.map { it.name } shouldBe listOf("Khamari")
            // Detail-only fields stay empty rather than aborting the import.
            khamari.promoters shouldBe emptyList()
            khamari.imageUrl.shouldBeNull()
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.METROPOL
    }
}
