package de.norm.events.scraper.hole44

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
 * Unit tests for [Hole44WebsiteImporter].
 *
 * Focuses on the overview ↔ detail merge: the detail page supplies the promoter, doors time,
 * image, and description, while the overview stands in (with its own complete data) whenever a
 * detail page fails to fetch.
 */
class Hole44WebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: Hole44WebsiteImporter

    private val overviewUrl = "https://hole-berlin.de/events/"
    private val municipalUrl = "https://hole-berlin.de/event/2026-08-02-municipal-waste/"
    private val lukeUrl = "https://hole-berlin.de/event/2026-11-20-luke-combs-uk-tribute/"
    private val kangUrl = "https://hole-berlin.de/event/2026-07-27-kang-yuchan/"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/hole44/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = Hole44WebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("hole44-overview.html"), overviewUrl),
                etag = "\"hole44-etag\"",
                lastModified = "Sun, 12 Jul 2026 03:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(municipalUrl) } returns
            Jsoup.parse(fixture("hole44-detail-standard.html"), municipalUrl)
        coEvery { htmlFetcher.fetchDocument(lukeUrl) } returns
            Jsoup.parse(fixture("hole44-detail-relocated.html"), lukeUrl)
        coEvery { htmlFetcher.fetchDocument(kangUrl) } returns
            Jsoup.parse(fixture("hole44-detail-simple.html"), kangUrl)

        // Every other event's detail page is unavailable, so the importer degrades to
        // overview data (which already carries a real date, so nothing is dropped).
        val stubbed = setOf(municipalUrl, lukeUrl, kangUrl)
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
    ): ScrapedEvent = events(result).first { it.sourceId == "hole44:$sourceIdSuffix" }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.HOLE44
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
            result.events shouldHaveSize 74
            result.etag shouldBe "\"hole44-etag\""
            result.lastModified shouldBe "Sun, 12 Jul 2026 03:00:00 GMT"
        }

    @Test
    fun `merges detail promoter, doors, image and description onto the overview event`() =
        runTest {
            val municipal = event(importer.importEvents(overviewUrl), "2026-08-02-municipal-waste")
            // From the detail page:
            municipal.promoters shouldContainExactly listOf("Trinity Music")
            municipal.doorsTime shouldBe LocalTime.of(19, 0)
            municipal.imageUrl.shouldNotBeNull()
            municipal.description.shouldNotBeNull()
            // Shared fields resolve consistently across both pages:
            municipal.eventDate shouldBe LocalDate.of(2026, 8, 2)
            municipal.startTime shouldBe LocalTime.of(20, 0)
            municipal.artists shouldContainExactly
                listOf(
                    ScrapedArtist("Municipal Waste", "HEADLINER"),
                    ScrapedArtist("Battlecreek", "SUPPORT")
                )
        }

    @Test
    fun `keeps the relocated status through the merge`() =
        runTest {
            event(importer.importEvents(overviewUrl), "2026-11-20-luke-combs-uk-tribute").status shouldBe "RELOCATED"
        }

    @Test
    fun `degrades to overview data when a detail page is unavailable`() =
        runTest {
            // Kate Ryan's detail page is not stubbed, so the merge falls back to the overview,
            // which still carries the relocation status and a real date.
            val kate = event(importer.importEvents(overviewUrl), "2026-10-29-kate-ryan")
            kate.status shouldBe "RELOCATED"
            kate.eventDate shouldBe LocalDate.of(2026, 10, 29)
        }
}
