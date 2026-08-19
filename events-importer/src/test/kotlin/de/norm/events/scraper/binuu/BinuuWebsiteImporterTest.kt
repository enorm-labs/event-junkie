package de.norm.events.scraper.binuu

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
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

/**
 * Unit tests for [BinuuWebsiteImporter].
 *
 * Focuses on the overview → detail merge behaviour: the detail payload is authoritative, and
 * events fall back to overview data when a detail page cannot be parsed.
 */
class BinuuWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: BinuuWebsiteImporter
    private val sourceUrl = "https://binuu.de/de/events"
    private val archEnemyUrl = "https://binuu.de/de/events/inzpqdgvi1eab2q"
    private val twdyUrl = "https://binuu.de/de/events/aufm93tii76xvp5"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/binuu/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = BinuuWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("binuu-overview.html"), sourceUrl),
                etag = "\"binuu-etag\"",
                lastModified = "Tue, 07 Jul 2026 10:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(archEnemyUrl) } returns
            Jsoup.parse(fixture("binuu-detail-arch-enemy.html"), archEnemyUrl)
        coEvery { htmlFetcher.fetchDocument(twdyUrl) } returns
            Jsoup.parse(fixture("binuu-detail-this-will-destroy-you.html"), twdyUrl)

        // Every other detail page: return a payload-less document so the importer
        // degrades to overview data for that event.
        coEvery {
            htmlFetcher.fetchDocument(match { it != archEnemyUrl && it != twdyUrl })
        } returns Jsoup.parse(EMPTY_DETAIL, sourceUrl)
    }

    private fun events(result: ImportResult): List<ScrapedEvent> {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.BINUU
    }

    @Test
    fun `imports all events and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 39
            result.etag shouldBe "\"binuu-etag\""
            result.lastModified shouldBe "Tue, 07 Jul 2026 10:00:00 GMT"
        }

    @Test
    fun `enriches an event from its detail page`() =
        runTest {
            val archEnemy = events(importer.importEvents(sourceUrl)).first { it.title == "Arch Enemy" }
            archEnemy.promoters shouldContainExactly listOf("Cobra Agency", "Festsaal Kreuzberg Booking")
            archEnemy.ticketUrl.shouldNotBeNull()
            archEnemy.artists shouldHaveSize 1
            archEnemy.soldOut shouldBe true
            archEnemy.description.shouldNotBeNull()
        }

    @Test
    fun `assigns support role from the detail page support line`() =
        runTest {
            val twdy = events(importer.importEvents(sourceUrl)).first { it.title == "This Will Destroy You" }
            twdy.artists.map { it.role } shouldContainExactly listOf("HEADLINER", "SUPPORT")
        }

    @Test
    fun `falls back to overview data when the detail page has no payload`() =
        runTest {
            // b56x1yt8xdz5oq5 has no mocked detail page → empty document → overview data is used.
            val bornToRoll =
                events(importer.importEvents(sourceUrl)).first { it.sourceId == "binuu:b56x1yt8xdz5oq5" }
            bornToRoll.eventDate shouldBe LocalDate.of(2026, 8, 8)
            // The overview payload carries no roster, so a detail-less event has no artists.
            bornToRoll.artists.shouldBeEmpty()
        }

    @Test
    fun `returns NotModified when the listing has not changed`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for an empty listing page`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body></body></html>", sourceUrl),
                    etag = null,
                    lastModified = null
                )

            events(importer.importEvents(sourceUrl)).shouldBeEmpty()
        }

    private companion object {
        private const val EMPTY_DETAIL = "<html><body></body></html>"
    }
}
