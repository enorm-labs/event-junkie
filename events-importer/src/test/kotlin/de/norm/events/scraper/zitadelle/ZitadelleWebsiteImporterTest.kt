package de.norm.events.scraper.zitadelle

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
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
 * Unit tests for [ZitadelleWebsiteImporter].
 *
 * Only three detail pages are stubbed; every other detail fetch throws, exercising the base
 * class's degrade-to-overview fallback.
 */
class ZitadelleWebsiteImporterTest {
    private lateinit var importer: ZitadelleWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://citadel-music-festival.de/events"

    private fun detailUrl(slug: String) = "https://citadel-music-festival.de/event/$slug"

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/zitadelle/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = ZitadelleWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture("zitadelle-overview.html"), sourceUrl),
                etag = "\"cmf-etag\"",
                lastModified = "Mon, 03 Aug 2026 09:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        listOf(
            "2026-08-15-antilopen-gang" to "antilopen-gang",
            "2026-08-19-off-days" to "off-days",
            "2027-08-13-alexander-marcus" to "alexander-marcus"
        ).forEach { (slug, fixture) ->
            coEvery { htmlFetcher.fetchDocument(detailUrl(slug)) } returns
                Jsoup.parse(readFixture("zitadelle-detail-$fixture.html"), detailUrl(slug))
        }
    }

    @Test
    fun `importEvents returns the whole season`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 8
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"cmf-etag\""
            result.lastModified shouldBe "Mon, 03 Aug 2026 09:00:00 GMT"
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
            val emptyDoc = Jsoup.parse("<html><body><div class='cmf-grid'></div></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `merges the detail page onto the listing's date`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val antilopen = result.events.first { it.sourceId == "zitadelle:2026-08-15-antilopen-gang" }

            // Listing owns the date; the detail page renders it only as German prose.
            antilopen.eventDate shouldBe LocalDate.of(2026, 8, 15)
            antilopen.startTime shouldBe LocalTime.of(17, 0)
            antilopen.soldOut shouldBe true
            antilopen.eventType shouldBe EventType.CONCERT.name
            // Detail-only.
            antilopen.doorsTime shouldBe LocalTime.of(17, 0)
            antilopen.subtitle shouldBe "Das goldene Antilopen Air"
            antilopen.description!!.isNotBlank() shouldBe true
            antilopen.ticketUrl!! shouldStartWith "https://www.eventim.de/"
            antilopen.promoters shouldBe listOf("Flux FM", "tip Berlin")
        }

    @Test
    fun `keeps a relocated show relocated through the merge`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val offDays = result.events.first { it.sourceId == "zitadelle:2026-08-19-off-days" }
            offDays.status shouldBe EventStatus.RELOCATED.name
            offDays.description!! shouldStartWith "Wird in die Columbiahalle verlegt."
        }

    @Test
    fun `falls back to listing data when a detail page cannot be fetched`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val omd = result.events.first { it.sourceId == "zitadelle:2026-08-18-omd" }

            // The card alone still carries a usable event.
            omd.title shouldBe "OMD"
            omd.eventDate shouldBe LocalDate.of(2026, 8, 18)
            omd.startTime shouldBe LocalTime.of(19, 0)
            omd.imageUrl!! shouldStartWith "https://citadel-music-festival.de/wp-content/uploads/"
            // Detail-only fields stay empty rather than aborting the import.
            omd.doorsTime.shouldBeNull()
            omd.description.shouldBeNull()
            omd.ticketUrl.shouldBeNull()
            omd.promoters.shouldBeEmpty()
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ZITADELLE
    }
}
