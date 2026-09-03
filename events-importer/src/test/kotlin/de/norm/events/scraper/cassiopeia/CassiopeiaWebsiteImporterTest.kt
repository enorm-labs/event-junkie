package de.norm.events.scraper.cassiopeia

import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [CassiopeiaWebsiteImporter].
 *
 * Uses static HTML fixtures that mirror the real Webflow CMS structure for
 * deterministic, offline-safe testing. The listing page fixture
 * (`cassiopeia-club.html`) provides fallback data, while per-event detail
 * page fixtures serve as the primary data source — matching the production
 * data flow where [CassiopeiaDetailPageScraper] is the primary extractor.
 *
 * [HtmlFetcher] is mocked so the importer returns fixture content without making real
 * HTTP requests. The clock is pinned to 2026-05-01 — before every fixture event — so the
 * scraper's past-event cutoff keeps all of them.
 */
class CassiopeiaWebsiteImporterTest {
    private lateinit var importer: CassiopeiaWebsiteImporter
    private lateinit var html: String
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC)

    private val sourceUrl = "https://cassiopeia-berlin.de/club"

    private val concertDetailHtml = loadFixture("scraper/cassiopeia/cassiopeia-detail-concert.html")
    private val partyDetailHtml = loadFixture("scraper/cassiopeia/cassiopeia-detail-party.html")
    private val cancelledDetailHtml = loadFixture("scraper/cassiopeia/cassiopeia-detail-cancelled.html")
    private val defaultDetailHtml = loadFixture("scraper/cassiopeia/cassiopeia-detail-default.html")

    @BeforeEach
    fun setUp() {
        importer = CassiopeiaWebsiteImporter(htmlFetcher, clock)
        html = loadFixture("scraper/cassiopeia/cassiopeia-club.html")

        val document = Jsoup.parse(html, sourceUrl)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = document,
                etag = null,
                lastModified = null
            )

        // Default: return minimal detail page for any URL (falls back to listing data)
        coEvery { htmlFetcher.fetchDocument(any()) } returns Jsoup.parse(defaultDetailHtml, sourceUrl)
        // Per-event detail pages with full data as primary source
        coEvery {
            htmlFetcher.fetchDocument("https://cassiopeia-berlin.de/event/super-tuesday-111639689")
        } returns Jsoup.parse(partyDetailHtml, "https://cassiopeia-berlin.de/event/super-tuesday-111639689")
        coEvery {
            htmlFetcher.fetchDocument("https://cassiopeia-berlin.de/event/pharmakon-111623735")
        } returns Jsoup.parse(concertDetailHtml, "https://cassiopeia-berlin.de/event/pharmakon-111623735")
        coEvery {
            htmlFetcher.fetchDocument("https://cassiopeia-berlin.de/event/cancelled-show-111111111")
        } returns Jsoup.parse(cancelledDetailHtml, "https://cassiopeia-berlin.de/event/cancelled-show-111111111")
    }

    private fun loadFixture(path: String): String =
        javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    @Test
    fun `importEvents extracts all events from fixture`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // 7 raw items on the page, but Döll is listed twice (legacy + CMS slug) → deduplicated to 6
            result.events shouldHaveSize 6
        }

    @Test
    fun `importEvents parses concert with sold-out badge`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val concert = result.events.first { it.title == "Grey City Fest Opener" }

            concert.eventDate shouldBe LocalDate.of(2026, 5, 14)
            concert.doorsTime shouldBe LocalTime.of(19, 0)
            concert.startTime shouldBe LocalTime.of(20, 0)
            concert.eventType shouldBe "CONCERT"
            concert.genre shouldBe "Punk"
            concert.imageUrl shouldBe "https://cdn.example.com/grey-city.png"
            concert.sourceUrl shouldBe "https://cassiopeia-berlin.de/event/grey-city-fest-opener-111601068"
            concert.sourceId shouldBe "cassiopeia:grey-city-fest-opener-111601068"
            concert.soldOut shouldBe true
            concert.status shouldBe "SCHEDULED"
        }

    @Test
    fun `importEvents filters an event-name title rather than minting a fake artist`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // "Grey City Fest Opener" is a festival slot name, not an artist. Even though
            // it is typed CONCERT, isNonArtistName filters it, so no artist is minted.
            val festival = result.events.first { it.title == "Grey City Fest Opener" }
            festival.eventType shouldBe "CONCERT"
            festival.artists.shouldBeEmpty()
        }

    @Test
    fun `importEvents extracts headliner and support artists from concert with support line`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val concert = result.events.first { it.title == "Pharmakon" }

            concert.eventType shouldBe "CONCERT"
            concert.genre shouldBe "Noise"
            concert.artists shouldContainExactly
                listOf(
                    ScrapedArtist(name = "Pharmakon", role = "HEADLINER"),
                    ScrapedArtist(name = "Aska", role = "SUPPORT")
                )
        }

    @Test
    fun `overview event type wins over a detail page classified as OTHER`() =
        runTest {
            // The detail page explicitly classifies Pharmakon as "Sonstiges" (OTHER), but the
            // overview lists it as "Konzert". OTHER is a weak signal, so the more specific
            // overview type must win rather than be overridden by the detail page's OTHER.
            val pharmakonUrl = "https://cassiopeia-berlin.de/event/pharmakon-111623735"
            val otherDetail =
                """
                <html><body><div class="modul-section events">
                <h1 class="event-date dark event">Pharmakon</h1>
                <div class="subheading invert gap">Sonstiges</div>
                <div class="date-wrapper">16 . 05 . 2026</div>
                </div></body></html>
                """.trimIndent()
            coEvery { htmlFetcher.fetchDocument(pharmakonUrl) } returns Jsoup.parse(otherDetail, pharmakonUrl)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val pharmakon = result.events.first { it.title == "Pharmakon" }
            pharmakon.eventType shouldBe "CONCERT"
        }

    @Test
    fun `importEvents parses party event with genre and detail page description`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val party = result.events.first { it.title == "Super Tuesday" }

            party.eventDate shouldBe LocalDate.of(2026, 5, 12)
            party.doorsTime shouldBe LocalTime.of(23, 0)
            party.startTime shouldBe LocalTime.of(23, 0)
            party.eventType shouldBe "PARTY"
            party.genre shouldBe "80s, Disco & Hip Hop"
            party.imageUrl shouldBe "https://cdn.example.com/super-tuesday.png"
            party.soldOut shouldBe false
            party.status shouldBe "SCHEDULED"
            party.description shouldContain "Super Tuesday!"
            party.description shouldContain "Freier Eintritt"
            party.ticketUrl shouldBe "https://cassiopeia.stager.co/shop/super/events/111639689"
            // Party events don't extract artists — titles are event names, not artist names
            party.artists.shouldBeEmpty()
        }

    @Test
    fun `importEvents detects cancelled status`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val cancelled = result.events.first { it.title == "Cancelled Show" }

            cancelled.status shouldBe "CANCELLED"
            cancelled.soldOut shouldBe false
        }

    @Test
    fun `importEvents handles missing image gracefully`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val noImage = result.events.first { it.title == "Cancelled Show" }

            noImage.imageUrl shouldBe null
        }

    @Test
    fun `importEvents handles year rollover correctly`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val newYear = result.events.first { it.title == "New Year Show" }

            newYear.eventDate shouldBe LocalDate.of(2027, 1, 15)
            newYear.eventType shouldBe "OTHER"
            newYear.genre shouldBe "Classic Indie"
        }

    @Test
    fun `importEvents returns empty list for page without events`() =
        runTest {
            val emptyHtml = "<html><body><div class='content-wrapper'></div></body></html>"
            val emptyDoc = Jsoup.parse(emptyHtml, sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(
                    document = emptyDoc,
                    etag = null,
                    lastModified = null
                )

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `importEvents continues without description when detail page fetch fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("Network error")

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 6
            // Events should still be parsed, just without description/ticketUrl
            val party = result.events.first { it.title == "Super Tuesday" }
            party.description shouldBe null
            party.ticketUrl shouldBe null

            // Without a detail page there are no "Support:" lines, but the overview
            // still yields the concert headliner from its title as a fallback.
            val concert = result.events.first { it.title == "Pharmakon" }
            concert.artists shouldContainExactly listOf(ScrapedArtist(name = "Pharmakon", role = "HEADLINER"))
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe de.norm.events.scraper.EventSource.CASSIOPEIA
    }

    @Test
    fun `importEvents deduplicates CMS duplicates and prefers canonical slug with numeric ID`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()

            // The fixture has two "Döll" entries: /event/doll (legacy) and /event/doell-111601080 (CMS canonical)
            val dollEvents = result.events.filter { it.title.trim() == "Döll" }
            dollEvents shouldHaveSize 1

            // The CMS canonical URL with numeric ID should be preferred over the legacy plain slug
            val doll = dollEvents.first()
            doll.sourceId shouldBe "cassiopeia:doell-111601080"
            doll.sourceUrl shouldBe "https://cassiopeia-berlin.de/event/doell-111601080"
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }
}
