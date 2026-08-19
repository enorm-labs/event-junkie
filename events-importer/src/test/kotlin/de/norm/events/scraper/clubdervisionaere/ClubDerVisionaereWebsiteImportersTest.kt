package de.norm.events.scraper.clubdervisionaere

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
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
import java.time.ZoneOffset

/**
 * Unit tests for the three room importers that share the Club der Visionäre programme
 * page ([ClubDerVisionaereWebsiteImporter], [SonnenraumWebsiteImporter],
 * [MsHoppetosseWebsiteImporter]).
 *
 * The clock is pinned before the fixture's earliest date (31.7.) so weekday-based year inference
 * stays deterministic.
 */
class ClubDerVisionaereWebsiteImportersTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZoneOffset.UTC)
    private val sourceUrl = "https://clubdervisionaere.com/programm/"

    private lateinit var clubImporter: ClubDerVisionaereWebsiteImporter
    private lateinit var sonnenraumImporter: SonnenraumWebsiteImporter
    private lateinit var boatImporter: MsHoppetosseWebsiteImporter

    @BeforeEach
    fun setUp() {
        clubImporter = ClubDerVisionaereWebsiteImporter(htmlFetcher, clock)
        sonnenraumImporter = SonnenraumWebsiteImporter(htmlFetcher, clock)
        boatImporter = MsHoppetosseWebsiteImporter(htmlFetcher, clock)

        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/clubdervisionaere/clubdervisionaere-programm.html")!!
                .bufferedReader()
                .readText()
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(html, sourceUrl),
                etag = "\"cdv-etag\"",
                lastModified = "Fri, 31 Jul 2026 09:12:00 GMT"
            )
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
}
