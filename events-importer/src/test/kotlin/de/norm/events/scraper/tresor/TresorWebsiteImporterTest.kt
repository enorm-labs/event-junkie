package de.norm.events.scraper.tresor

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
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
 * Unit tests for [TresorWebsiteImporter], focused on the merge: the event page supplies the start
 * time and blurb, while the listing keeps the title and the curated lineup.
 */
class TresorWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: TresorWebsiteImporter

    private val listingUrl = "https://tresorberlin.com/club/events/"
    private val klubnachtUrl = "https://tresorberlin.com/event/20260801-tresor-klubnacht/"
    private val aquabahnUrl = "https://tresorberlin.com/event/20260807-tresor-aquabahn-x-mechatronica/"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/tresor/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = TresorWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("tresor-events.html"), listingUrl),
                etag = "\"tresor-etag\"",
                lastModified = "Sat, 01 Aug 2026 03:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(klubnachtUrl) } returns Jsoup.parse(fixture("tresor-detail-klubnacht.html"), klubnachtUrl)
        coEvery { htmlFetcher.fetchDocument(aquabahnUrl) } returns Jsoup.parse(fixture("tresor-detail-blurb.html"), aquabahnUrl)

        val stubbed = setOf(klubnachtUrl, aquabahnUrl)
        coEvery { htmlFetcher.fetchDocument(match { it !in stubbed }) } returns Jsoup.parse("<html><body></body></html>", listingUrl)
    }

    private fun event(
        result: ImportResult,
        slug: String
    ): ScrapedEvent {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events.first { it.sourceId == "tresor:$slug" }
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.TRESOR
    }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(listingUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports all events and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(listingUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 30
            result.etag shouldBe "\"tresor-etag\""
        }

    @Test
    fun `merges the event page's start time and blurb onto the listing entry`() =
        runTest {
            val klubnacht = event(importer.importEvents(listingUrl), "20260801-tresor-klubnacht")
            klubnacht.startTime shouldBe LocalTime.of(23, 0)
            klubnacht.description.shouldNotBeNull()
            // …while the listing keeps the title and the floor-grouped lineup.
            klubnacht.title shouldBe "Tresor Klubnacht"
            klubnacht.eventDate shouldBe LocalDate.of(2026, 8, 1)
            klubnacht.artists.map { it.stage }.distinct() shouldBe listOf("Tresor", "Globus")
            klubnacht.genre shouldBe "Techno"
        }

    @Test
    fun `degrades to listing data when an event page is unavailable`() =
        runTest {
            val other = event(importer.importEvents(listingUrl), "20260805-tresor-new-faces-hosted-by-e2nmn")
            other.title shouldBe "Tresor New Faces hosted by E2NMN"
            other.eventDate shouldBe LocalDate.of(2026, 8, 5)
            other.artists.isNotEmpty() shouldBe true
        }
}
