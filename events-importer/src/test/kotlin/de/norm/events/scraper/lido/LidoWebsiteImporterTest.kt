package de.norm.events.scraper.lido

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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Unit tests for [LidoWebsiteImporter].
 *
 * Focuses on the overview ↔ detail merge behaviour: the detail page supplies
 * prices/description/ticket/image, while the overview keeps the date, status, sold-out flag, and
 * artist roster the detail header omits.
 */
class LidoWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: LidoWebsiteImporter
    private val sourceUrl = "https://www.lido-berlin.de/"
    private val sorryUrl = "https://www.lido-berlin.de/events/2026-06-15-sorry"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/lido/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = LidoWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("lido-overview.html"), sourceUrl),
                etag = "\"lido-etag\"",
                lastModified = "Wed, 17 Jun 2026 10:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(sorryUrl) } returns
            Jsoup.parse(fixture("lido-detail-sorry.html"), sorryUrl)

        // Every other event's detail page is unavailable, so the importer degrades
        // to overview data (which already carries a real date, so nothing is dropped).
        coEvery { htmlFetcher.fetchDocument(match { it != sorryUrl }) } returns
            Jsoup.parse("<html><body></body></html>", sourceUrl)
    }

    private fun events(result: ImportResult): List<ScrapedEvent> {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.LIDO
    }

    @Test
    fun `imports all events and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 5
            result.etag shouldBe "\"lido-etag\""
            result.lastModified shouldBe "Wed, 17 Jun 2026 10:00:00 GMT"
        }

    @Test
    fun `merges detail prices, description and image with overview date and artists`() =
        runTest {
            val sorry = events(importer.importEvents(sourceUrl)).first { it.title == "SORRY" }
            // From the detail page:
            sorry.pricePresale shouldBe BigDecimal("25.00")
            sorry.priceBoxOffice shouldBe BigDecimal("30.00")
            sorry.ticketUrl!! shouldContain "tixforgigs.com"
            sorry.imageUrl!! shouldContain "kulturhaeuser-production"
            sorry.description.shouldNotBeNull()
            // From the overview page (the detail header has neither date nor roster):
            sorry.eventDate shouldBe LocalDate.of(2026, 6, 15)
            sorry.eventType shouldBe "CONCERT"
            sorry.promoters shouldContainExactly listOf("Puschen")
            sorry.artists shouldContainExactly
                listOf(
                    ScrapedArtist("SORRY", "HEADLINER"),
                    ScrapedArtist("SNAKE ORANGE CAKE", "SUPPORT")
                )
        }

    @Test
    fun `keeps the overview sold-out flag when the detail page is unavailable`() =
        runTest {
            val publicEnemy = events(importer.importEvents(sourceUrl)).first { it.title == "PUBLIC ENEMY" }
            publicEnemy.soldOut shouldBe true
        }

    @Test
    fun `keeps the overview cancelled status when the detail page is unavailable`() =
        runTest {
            val pangea = events(importer.importEvents(sourceUrl)).first { it.title == "TOGETHER PANGEA" }
            pangea.status shouldBe "CANCELLED"
        }
}
