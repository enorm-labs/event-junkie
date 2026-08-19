package de.norm.events.scraper.quasimodo

import de.norm.events.event.EventType
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
 * Unit tests for [QuasimodoWebsiteImporter].
 *
 * Only the four detail pages captured as fixtures are stubbed; every other detail fetch throws,
 * exercising the base class's degrade-to-overview fallback.
 */
class QuasimodoWebsiteImporterTest {
    private lateinit var importer: QuasimodoWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://quasimodo.club/events"

    private val detailFixtures =
        mapOf(
            "otis-kane-7410" to "otis-kane",
            "we-love-80s-37-7300" to "we-love-80s",
            "disco-inferno-65-7289" to "disco-inferno",
            "berlin-beat-invasion-no-8-7316" to "berlin-beat-invasion"
        )

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/quasimodo/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = QuasimodoWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture("quasimodo-overview.html"), sourceUrl),
                etag = "\"quasimodo-etag\"",
                lastModified = "Sat, 01 Aug 2026 10:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        detailFixtures.forEach { (slug, fixture) ->
            val detailUrl = "https://quasimodo.club/events/$slug"
            coEvery { htmlFetcher.fetchDocument(detailUrl) } returns
                Jsoup.parse(readFixture("quasimodo-detail-$fixture.html"), detailUrl)
        }
    }

    @Test
    fun `importEvents extracts all events from fixture`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 26
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"quasimodo-etag\""
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
    fun `importEvents returns empty list for a page without cards`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><div class='em-events-list'></div></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `merges the detail page over the listing while keeping the listing's date`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val otisKane = result.events.first { it.sourceId == "quasimodo:otis-kane-7410" }

            // Listing-only: the detail page parses no date at all.
            otisKane.eventDate shouldBe LocalDate.of(2026, 10, 8)
            otisKane.startTime shouldBe LocalTime.of(22, 0)
            // Detail-only.
            otisKane.doorsTime shouldBe LocalTime.of(21, 0)
            otisKane.pricePresale shouldBe BigDecimal("30")
            otisKane.promoters shouldBe listOf("FKP Scorpio")
            otisKane.description!!.isNotBlank() shouldBe true
        }

    @Test
    fun `lets the detail category flip a night to a party and drop its artists`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // The listing alone would guess CONCERT for "Disco Inferno" and mint it as an artist.
            val discoInferno = result.events.first { it.sourceId == "quasimodo:disco-inferno-65-7289" }
            discoInferno.eventType shouldBe EventType.PARTY.name
            discoInferno.artists.shouldBeEmpty()
            discoInferno.eventDate shouldBe LocalDate.of(2026, 10, 2)
        }

    @Test
    fun `falls back to listing data when a detail page cannot be fetched`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // Ella Eyre has no detail fixture, so its detail fetch throws.
            val ellaEyre = result.events.first { it.sourceId == "quasimodo:ella-eyre-7420" }
            ellaEyre.title shouldBe "Ella Eyre"
            ellaEyre.eventDate shouldBe LocalDate.of(2026, 10, 26)
            ellaEyre.startTime shouldBe LocalTime.of(22, 0)
            ellaEyre.genre shouldBe "Pop, R'n'B, Singer-Songwriter"
            ellaEyre.artists.map { it.name } shouldBe listOf("Ella Eyre")
            // Detail-only fields stay empty rather than aborting the import.
            ellaEyre.doorsTime.shouldBeNull()
            ellaEyre.pricePresale.shouldBeNull()
            ellaEyre.promoters.shouldBeEmpty()
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.QUASIMODO
    }
}
