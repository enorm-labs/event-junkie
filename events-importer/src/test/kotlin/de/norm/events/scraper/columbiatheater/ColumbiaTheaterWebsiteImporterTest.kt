package de.norm.events.scraper.columbiatheater

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * Unit tests for [ColumbiaTheaterWebsiteImporter].
 *
 * Focuses on the overview ↔ detail merge: the detail page supplies the times, description, ticket
 * URL and presenters, while the overview stands in (with its own complete data) whenever a detail
 * page fails to fetch.
 */
class ColumbiaTheaterWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: ColumbiaTheaterWebsiteImporter

    private val overviewUrl = "https://columbia-theater.de/"
    private val soulflyUrl = "https://columbia-theater.de/event/20260803-soulfly/"
    private val templesUrl = "https://columbia-theater.de/event/20261029-temples/"
    private val turbopaoloUrl = "https://columbia-theater.de/event/20260928-turbopaolo/"
    private val kmfdmUrl = "https://columbia-theater.de/event/20270325-kmfdm/"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/columbiatheater/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = ColumbiaTheaterWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("columbiatheater-overview.html"), overviewUrl),
                etag = "\"columbia-etag\"",
                lastModified = "Sat, 01 Aug 2026 03:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(soulflyUrl) } returns
            Jsoup.parse(fixture("columbiatheater-detail-concert.html"), soulflyUrl)
        coEvery { htmlFetcher.fetchDocument(templesUrl) } returns
            Jsoup.parse(fixture("columbiatheater-detail-presenters.html"), templesUrl)
        coEvery { htmlFetcher.fetchDocument(turbopaoloUrl) } returns
            Jsoup.parse(fixture("columbiatheater-detail-relocated.html"), turbopaoloUrl)
        coEvery { htmlFetcher.fetchDocument(kmfdmUrl) } returns
            Jsoup.parse(fixture("columbiatheater-detail-postponed.html"), kmfdmUrl)

        // Every other event's detail page is unavailable, so the importer degrades to overview
        // data (which already carries a real date, so nothing is dropped).
        val stubbed = setOf(soulflyUrl, templesUrl, turbopaoloUrl, kmfdmUrl)
        coEvery { htmlFetcher.fetchDocument(match { it !in stubbed }) } returns
            Jsoup.parse("<html><body></body></html>", overviewUrl)
    }

    private fun events(result: ImportResult): List<ScrapedEvent> {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    private fun event(
        result: ImportResult,
        sourceIdSuffix: String
    ): ScrapedEvent = events(result).first { it.sourceId == "columbia_theater:$sourceIdSuffix" }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.COLUMBIA_THEATER
    }

    @Test
    fun `returns NotModified when the overview is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(overviewUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports all events and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 98
            result.etag shouldBe "\"columbia-etag\""
            result.lastModified shouldBe "Sat, 01 Aug 2026 03:00:00 GMT"
        }

    @Test
    fun `merges detail times, description and ticket onto the overview event`() =
        runTest {
            val soulfly = event(importer.importEvents(overviewUrl), "20260803-soulfly")
            // Only the detail page carries these:
            soulfly.startTime shouldBe LocalTime.of(20, 0)
            soulfly.doorsTime shouldBe LocalTime.of(19, 0)
            soulfly.description.shouldNotBeNull()
            soulfly.ticketUrl.shouldNotBeNull()
            // Shared fields resolve consistently across both pages:
            soulfly.eventDate shouldBe LocalDate.of(2026, 8, 3)
            soulfly.imageUrl shouldBe "https://columbia-theater.de/wp-content/uploads/2026/05/image-1024x683.webp"
            soulfly.artists shouldContainExactly
                listOf(
                    ScrapedArtist("Soulfly", "HEADLINER"),
                    ScrapedArtist("Botulism", "SUPPORT")
                )
        }

    @Test
    fun `merges the detail page's media presenters`() =
        runTest {
            event(importer.importEvents(overviewUrl), "20261029-temples").promoters shouldContainExactly
                listOf("DIFFUS", "Bedroomdisco", "MusikBlog", "FluxFM", "Musikexpress")
        }

    @Test
    fun `keeps the relocated and postponed statuses through the merge`() =
        runTest {
            val result = importer.importEvents(overviewUrl)
            event(result, "20260928-turbopaolo").status shouldBe "RELOCATED"
            event(result, "20270325-kmfdm").status shouldBe "POSTPONED"
        }

    @Test
    fun `degrades to overview data when a detail page is unavailable`() =
        runTest {
            // This event's detail page is not stubbed, so the merge falls back to the overview,
            // which still carries a real date, title and lineup — but no times.
            val despisedIcon = event(importer.importEvents(overviewUrl), "20261118-despised-icon-carnifex-suffocation")
            despisedIcon.title shouldBe "Despised Icon / Carnifex / Suffocation"
            despisedIcon.eventDate shouldBe LocalDate.of(2026, 11, 18)
            despisedIcon.startTime.shouldBeNull()
        }
}
