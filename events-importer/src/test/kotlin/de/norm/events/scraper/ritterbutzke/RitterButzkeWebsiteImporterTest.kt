package de.norm.events.scraper.ritterbutzke

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
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
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [RitterButzkeWebsiteImporter].
 *
 * Only the three detail pages captured as fixtures are stubbed; every other detail fetch throws,
 * exercising the base class's degrade-to-overview fallback.
 */
class RitterButzkeWebsiteImporterTest {
    private lateinit var importer: RitterButzkeWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://club.ritterbutzke.com/events"

    private val detailFixtures =
        mapOf(
            "070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia" to "unison",
            "310726-DeeportamentCommunityw-NicoMorano-OpenAir-Indoor" to "deeportament",
            "100427-WeltauswahlbyExtrawelt" to "extrawelt"
        )

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/ritterbutzke/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = RitterButzkeWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture("ritterbutzke-overview.html"), sourceUrl),
                etag = "\"rb-etag\"",
                lastModified = "Sun, 03 Aug 2026 08:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        detailFixtures.forEach { (slug, fixture) ->
            val detailUrl = "https://club.ritterbutzke.com/event/$slug"
            coEvery { htmlFetcher.fetchDocument(detailUrl) } returns
                Jsoup.parse(readFixture("ritterbutzke-detail-$fixture.html"), detailUrl)
        }
    }

    @Test
    fun `importEvents extracts all events from fixture`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 29
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"rb-etag\""
            result.lastModified shouldBe "Sun, 03 Aug 2026 08:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns empty list for a page without cards`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><div class='events-container'></div></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `merges the detail page over the listing`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val unison =
                result.events.first { it.sourceId == "ritter_butzke:070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia" }

            unison.eventDate shouldBe LocalDate.of(2026, 8, 7)
            unison.startTime shouldBe LocalTime.of(22, 0)
            unison.ticketUrl!!.startsWith("https://tickets.ritterbutzke.com/") shouldBe true
            unison.artists shouldHaveSize 9
        }

    @Test
    fun `keeps the rendered date of a moved show through the merge`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val deeportament =
                result.events.first { it.sourceId == "ritter_butzke:310726-DeeportamentCommunityw-NicoMorano-OpenAir-Indoor" }
            deeportament.eventDate shouldBe LocalDate.of(2026, 9, 4)
            deeportament.artists shouldHaveSize 7
        }

    @Test
    fun `falls back to listing data when a detail page cannot be fetched`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // Conrad Taylor has no detail fixture, so its detail fetch throws.
            val conradTaylor = result.events.first { it.sourceId == "ritter_butzke:031026-ConradTaylor" }
            conradTaylor.title shouldBe "Conrad Taylor"
            conradTaylor.eventDate shouldBe LocalDate.of(2026, 10, 3)
            // Detail-only fields stay empty rather than aborting the import.
            conradTaylor.startTime.shouldBeNull()
            conradTaylor.ticketUrl.shouldBeNull()
            conradTaylor.artists.shouldBeEmpty()
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.RITTER_BUTZKE
    }
}
