package de.norm.events.scraper.mikropol

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
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
 * Unit tests for [MikropolWebsiteImporter].
 *
 * Focuses on the overview ↔ detail merge: the detail page supplies the description, image, and
 * ticket URL, while the overview stands in (with its own complete data) whenever a detail page
 * fails to fetch.
 */
class MikropolWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: MikropolWebsiteImporter

    private val overviewUrl = "https://mikropol-berlin.de/events/"
    private val houseUrl = "https://mikropol-berlin.de/event/2026-07-14-house-of-protection/"
    private val vowwsUrl = "https://mikropol-berlin.de/event/2026-07-25-vowws/"
    private val cultureWarsUrl = "https://mikropol-berlin.de/event/2026-07-16-verlegt-in-den-frannz-club-culture-wars/"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/mikropol/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = MikropolWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("mikropol-overview.html"), overviewUrl),
                etag = "\"mikropol-etag\"",
                lastModified = "Mon, 13 Jul 2026 03:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(houseUrl) } returns
            Jsoup.parse(fixture("mikropol-detail-soldout.html"), houseUrl)
        coEvery { htmlFetcher.fetchDocument(vowwsUrl) } returns
            Jsoup.parse(fixture("mikropol-detail-cancelled.html"), vowwsUrl)
        coEvery { htmlFetcher.fetchDocument(cultureWarsUrl) } returns
            Jsoup.parse(fixture("mikropol-detail-relocated.html"), cultureWarsUrl)

        // Every other event's detail page is unavailable, so the importer degrades to overview
        // data (which already carries a real date, so nothing is dropped).
        val stubbed = setOf(houseUrl, vowwsUrl, cultureWarsUrl)
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
    ): ScrapedEvent = events(result).first { it.sourceId == "mikropol:$sourceIdSuffix" }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.MIKROPOL
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
            result.events shouldHaveSize 60
            result.etag shouldBe "\"mikropol-etag\""
            result.lastModified shouldBe "Mon, 13 Jul 2026 03:00:00 GMT"
        }

    @Test
    fun `merges detail description, image and ticket onto the sold-out event`() =
        runTest {
            val house = event(importer.importEvents(overviewUrl), "2026-07-14-house-of-protection")
            // From the detail page:
            house.description.shouldNotBeNull()
            house.imageUrl.shouldNotBeNull()
            house.ticketUrl.shouldNotBeNull()
            // Shared fields resolve consistently across both pages:
            house.soldOut shouldBe true
            house.eventDate shouldBe LocalDate.of(2026, 7, 14)
            house.startTime shouldBe LocalTime.of(20, 0)
            house.doorsTime shouldBe LocalTime.of(19, 0)
            house.artists shouldContainExactly
                listOf(
                    ScrapedArtist("HOUSE OF PROTECTION", "HEADLINER"),
                    ScrapedArtist("noise of the voiceless", "SUPPORT")
                )
        }

    @Test
    fun `keeps the cancelled status through the merge`() =
        runTest {
            event(importer.importEvents(overviewUrl), "2026-07-25-vowws").status shouldBe "CANCELLED"
        }

    @Test
    fun `degrades to overview data when a detail page is unavailable`() =
        runTest {
            // This event's detail page is not stubbed, so the merge falls back to the overview,
            // which still carries a real date and title.
            val glu = event(importer.importEvents(overviewUrl), "2026-07-21-glu")
            glu.title shouldBe "GLU"
            glu.eventDate shouldBe LocalDate.of(2026, 7, 21)
        }
}
