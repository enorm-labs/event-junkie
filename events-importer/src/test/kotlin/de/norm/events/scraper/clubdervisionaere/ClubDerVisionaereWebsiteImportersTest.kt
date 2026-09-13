package de.norm.events.scraper.clubdervisionaere

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
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
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for the three room importers that share the Club der Visionäre programme
 * page ([ClubDerVisionaereWebsiteImporter], [SonnenraumWebsiteImporter],
 * [MsHoppetosseWebsiteImporter]).
 *
 * The clock is pinned before the fixture's earliest date (31.7.) so weekday-based year inference
 * stays deterministic. The July programme has no homepage snapshot beside it, so its tests
 * run with the homepage fetch failing — the nights then carry no time. The September pair
 * (`-programm-september.html` + `-home-september.html`, saved the same day) is what proves the
 * join.
 */
class ClubDerVisionaereWebsiteImportersTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZoneOffset.UTC)
    private val sourceUrl = "https://clubdervisionaere.com/programm/"
    private val homeUrl = "https://clubdervisionaere.com/"

    private lateinit var clubImporter: ClubDerVisionaereWebsiteImporter
    private lateinit var sonnenraumImporter: SonnenraumWebsiteImporter
    private lateinit var boatImporter: MsHoppetosseWebsiteImporter

    @BeforeEach
    fun setUp() {
        clubImporter = ClubDerVisionaereWebsiteImporter(htmlFetcher, clock)
        sonnenraumImporter = SonnenraumWebsiteImporter(htmlFetcher, clock)
        boatImporter = MsHoppetosseWebsiteImporter(htmlFetcher, clock)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = fixture("clubdervisionaere-programm.html", sourceUrl),
                etag = "\"cdv-etag\"",
                lastModified = "Fri, 31 Jul 2026 09:12:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(homeUrl) } throws HttpFetchException(HTTP_NOT_FOUND, homeUrl)
    }

    private fun fixture(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/clubdervisionaere/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    /** Points the fetcher at the September pair, whose homepage lists the programme's nights with times. */
    private fun useSeptemberPair() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = fixture("clubdervisionaere-programm-september.html", sourceUrl), etag = null, lastModified = null)
        coEvery { htmlFetcher.fetchDocument(homeUrl) } returns fixture("clubdervisionaere-home-september.html", homeUrl)
    }

    @Test
    fun `each room importer declares its own event source`() {
        clubImporter.eventSource shouldBe EventSource.CLUB_DER_VISIONAERE
        sonnenraumImporter.eventSource shouldBe EventSource.SONNENRAUM
        boatImporter.eventSource shouldBe EventSource.MS_HOPPETOSSE
    }

    @Test
    fun `importEvents keeps only its own room from the shared listing and propagates conditional headers`() =
        runTest {
            val result = clubImporter.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 17
            result.events.first().title shouldBe "Wordless"
            result.etag shouldBe "\"cdv-etag\""
            result.lastModified shouldBe "Fri, 31 Jul 2026 09:12:00 GMT"

            val sonnenraum = sonnenraumImporter.importEvents(sourceUrl)
            sonnenraum.shouldBeInstanceOf<ImportResult.Success>()
            sonnenraum.events shouldHaveSize 3
            sonnenraum.events.first().sourceId shouldBe "sonnenraum:41733"
        }

    @Test
    fun `importEvents stores no start time when the homepage cannot be fetched`() =
        runTest {
            val result = clubImporter.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 17
            result.events.forEach { it.startTime.shouldBeNull() }
        }

    @Test
    fun `importEvents joins the homepage's start time onto each room's nights by post id`() =
        runTest {
            useSeptemberPair()
            val septemberClock = Clock.fixed(Instant.parse("2026-09-13T12:00:00Z"), ZoneOffset.UTC)

            val boat = MsHoppetosseWebsiteImporter(htmlFetcher, septemberClock).importEvents(sourceUrl)
            boat.shouldBeInstanceOf<ImportResult.Success>()
            boat.events.map { it.sourceId to it.startTime } shouldBe
                listOf(
                    "ms_hoppetosse:7227" to LocalTime.of(23, 0),
                    "ms_hoppetosse:7226" to LocalTime.of(23, 0),
                    "ms_hoppetosse:7228" to LocalTime.of(22, 0)
                )

            val sonnenraum = SonnenraumWebsiteImporter(htmlFetcher, septemberClock).importEvents(sourceUrl)
            sonnenraum.shouldBeInstanceOf<ImportResult.Success>()
            sonnenraum.events.map { it.startTime } shouldBe listOf(LocalTime.of(20, 30), LocalTime.of(20, 30))
        }

    @Test
    fun `importEvents leaves a night the homepage does not list without a time`() =
        runTest {
            useSeptemberPair()
            val club = ClubDerVisionaereWebsiteImporter(htmlFetcher, Clock.fixed(Instant.parse("2026-09-13T12:00:00Z"), ZoneOffset.UTC))

            val result = club.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 6
            // Today's night sits in the homepage's TODAY box, which carries no post id — so no time.
            result.events.first().sourceId shouldBe "club_der_visionaere:41860"
            result.events
                .first()
                .startTime
                .shouldBeNull()
            result.events[1].startTime shouldBe LocalTime.of(18, 0) // Mo. 14.9.   06:00 p.m.
            result.events.last().startTime shouldBe LocalTime.of(15, 0) // Fr. 18.9.   03:00 p.m.
        }

    @Test
    fun `importEvents returns no events for a room that is out of season`() =
        runTest {
            // The boat is the winter location — nothing of its own on a summer listing.
            val result = boatImporter.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `importEvents returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified
            clubImporter.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns an empty list for a page without events`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><p>No events</p></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = clubImporter.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
