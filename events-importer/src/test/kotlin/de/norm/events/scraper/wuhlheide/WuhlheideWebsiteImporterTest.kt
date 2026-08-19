package de.norm.events.scraper.wuhlheide

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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [WuhlheideWebsiteImporter].
 *
 * Only the three detail pages captured as fixtures are stubbed; every other detail fetch throws,
 * exercising the base class's degrade-to-overview fallback.
 */
class WuhlheideWebsiteImporterTest {
    private lateinit var importer: WuhlheideWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.wuhlheide.de/programm"

    private val detailFixtures =
        mapOf(
            "alligatoah/2026-08-01" to "alligatoah",
            "annenmay-wbr-kantereit/2026-08-13" to "annenmaykantereit",
            "die-aerzte/2027-06-05" to "die-aerzte"
        )

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/wuhlheide/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = WuhlheideWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture("wuhlheide-overview.html"), sourceUrl),
                etag = "\"wuhlheide-etag\"",
                lastModified = "Sat, 01 Aug 2026 09:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        detailFixtures.forEach { (slug, fixture) ->
            val detailUrl = "https://www.wuhlheide.de/programm/$slug"
            coEvery { htmlFetcher.fetchDocument(detailUrl) } returns
                Jsoup.parse(readFixture("wuhlheide-detail-$fixture.html"), detailUrl)
        }
    }

    @Test
    fun `importEvents extracts all events from fixture`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 16
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"wuhlheide-etag\""
            result.lastModified shouldBe "Sat, 01 Aug 2026 09:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns empty list for a page without shows`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><div class='shows'></div></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `merges the detail page over the listing while keeping the listing's subtitle`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val alligatoah = result.events.first { it.sourceId == "wuhlheide:alligatoah/2026-08-01" }

            // Detail-only fields.
            alligatoah.doorsTime shouldBe LocalTime.of(17, 0)
            alligatoah.startTime shouldBe LocalTime.of(19, 0)
            alligatoah.pricePresale shouldBe BigDecimal("69.90")
            alligatoah.promoters shouldBe listOf("Boldt Berlin Konzertagentur")
            // Listing-only fields.
            alligatoah.subtitle shouldBe "20 Jahre - Jubiläumskonzert"
            alligatoah.soldOut shouldBe false
        }

    @Test
    fun `keeps the listing's subtitle and sold-out flag over the detail page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val kantereit = result.events.first { it.sourceId == "wuhlheide:annenmay-wbr-kantereit/2026-08-13" }

            // The detail h3 is an admin notice; the listing has no subtitle for this night.
            kantereit.subtitle.shouldBeNull()
            // Only the listing carries the sold-out badge.
            kantereit.soldOut shouldBe true
            kantereit.ticketUrl.shouldBeNull()
            kantereit.pricePresale.shouldBeNull()
        }

    @Test
    fun `falls back to listing data when a detail page cannot be fetched`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // Deftones has no detail fixture, so its detail fetch throws.
            val deftones = result.events.first { it.sourceId == "wuhlheide:deftones/2026-08-18" }
            deftones.title shouldBe "Deftones"
            deftones.subtitle shouldBe "Summer 2026"
            deftones.eventDate shouldBe LocalDate.of(2026, 8, 18)
            deftones.artists.map { it.name } shouldBe listOf("Deftones")
            // Detail-only fields stay empty rather than aborting the import.
            deftones.doorsTime.shouldBeNull()
            deftones.pricePresale.shouldBeNull()
            deftones.promoters.shouldBeEmpty()
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.WUHLHEIDE
    }
}
