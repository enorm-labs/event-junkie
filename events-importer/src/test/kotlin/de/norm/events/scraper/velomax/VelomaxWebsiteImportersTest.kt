package de.norm.events.scraper.velomax

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
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
 * Unit tests for the three [AbstractVelomaxHallImporter] subclasses.
 *
 * All three read the same listing, so the tests focus on what distinguishes them — each importing
 * only its own hall — and on the merge, where the listing's event type and sold-out signal must
 * survive the otherwise-authoritative Microdata detail page.
 */
class VelomaxWebsiteImportersTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val overviewUrl = "https://www.velomax.de/events"
    private val jojiUrl = "https://www.velodrom.de/events/event/joji-velodrom-2026-08-29"
    private val showUrl = "https://www.max-schmeling-halle.de/events/event/die-nervigen-max-schmeling-halle-2026-10-23"
    private val reezyUrl = "https://www.ufo-velodrom.de/events/event/reezy-ufo-2026-09-12"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/velomax/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("velomax-overview.html"), overviewUrl),
                etag = "\"velomax-etag\"",
                lastModified = "Sat, 01 Aug 2026 03:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(jojiUrl) } returns Jsoup.parse(fixture("velomax-detail-concert.html"), jojiUrl)
        coEvery { htmlFetcher.fetchDocument(showUrl) } returns Jsoup.parse(fixture("velomax-detail-show.html"), showUrl)
        coEvery { htmlFetcher.fetchDocument(reezyUrl) } returns Jsoup.parse(fixture("velomax-detail-ufo.html"), reezyUrl)

        val stubbed = setOf(jojiUrl, showUrl, reezyUrl)
        coEvery { htmlFetcher.fetchDocument(match { it !in stubbed }) } returns Jsoup.parse("<html><body></body></html>", overviewUrl)
    }

    private fun events(result: ImportResult): List<ScrapedEvent> {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    @Test
    fun `each hall importer declares its own event source`() {
        MaxSchmelingHalleWebsiteImporter(htmlFetcher).eventSource shouldBe EventSource.MAX_SCHMELING_HALLE
        VelodromWebsiteImporter(htmlFetcher).eventSource shouldBe EventSource.VELODROM
        UfoImVelodromWebsiteImporter(htmlFetcher).eventSource shouldBe EventSource.UFO_IM_VELODROM
    }

    @Test
    fun `each hall importer takes only its own share of the shared listing`() =
        runTest {
            events(MaxSchmelingHalleWebsiteImporter(htmlFetcher).importEvents(overviewUrl)) shouldHaveSize 18
            events(VelodromWebsiteImporter(htmlFetcher).importEvents(overviewUrl)) shouldHaveSize 25
            events(UfoImVelodromWebsiteImporter(htmlFetcher).importEvents(overviewUrl)) shouldHaveSize 10
        }

    @Test
    fun `returns NotModified when the shared listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns FetchResult.NotModified
            VelodromWebsiteImporter(htmlFetcher).importEvents(overviewUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `propagates the listing's conditional headers`() =
        runTest {
            val result = VelodromWebsiteImporter(htmlFetcher).importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"velomax-etag\""
            result.lastModified shouldBe "Sat, 01 Aug 2026 03:00:00 GMT"
        }

    @Test
    fun `merges the Microdata detail page onto the listing entry`() =
        runTest {
            val joji = events(VelodromWebsiteImporter(htmlFetcher).importEvents(overviewUrl)).first { it.title == "Joji" }
            // Only the detail page carries these:
            joji.doorsTime shouldBe LocalTime.of(18, 0)
            joji.description.shouldNotBeNull()
            joji.ticketUrl.shouldNotBeNull()
            joji.imageUrl.shouldNotBeNull()
            joji.promoters shouldContainExactly listOf("Live Nation GmbH")
            // Shared fields resolve consistently:
            joji.eventDate shouldBe LocalDate.of(2026, 8, 29)
            joji.startTime shouldBe LocalTime.of(20, 0)
        }

    @Test
    fun `keeps the listing's event type, which the detail page does not restate`() =
        runTest {
            // The Microdata says nothing about concert-vs-show; only the listing's data-type does.
            val show =
                events(MaxSchmelingHalleWebsiteImporter(htmlFetcher).importEvents(overviewUrl))
                    .first { it.sourceId.contains("die-nervigen") }
            show.eventType shouldBe "SHOW"
        }

    @Test
    fun `keeps the listing's session-keyed sourceId, which the shared detail page cannot supply`() =
        runTest {
            // The detail page derives its id from a permalink that is one page per show, so letting
            // it win would hand every session of a day the same id — and `event.source_id` is
            // UNIQUE. The listing's id, with its start-time suffix, has to survive the merge.
            val joji = events(VelodromWebsiteImporter(htmlFetcher).importEvents(overviewUrl)).first { it.title == "Joji" }
            joji.sourceId shouldBe "velodrom:joji-velodrom-2026-08-29-2000"
        }

    @Test
    fun `imports every session of a run that plays more than once in a day`() =
        runTest {
            val sessions =
                events(VelodromWebsiteImporter(htmlFetcher).importEvents(overviewUrl))
                    .filter { it.title.startsWith("Disney On Ice") }
            sessions shouldHaveSize 6
            sessions.map { it.sourceId }.distinct() shouldHaveSize 6
        }

    @Test
    fun `degrades to listing data when a detail page is unavailable`() =
        runTest {
            val unstubbed =
                events(VelodromWebsiteImporter(htmlFetcher).importEvents(overviewUrl))
                    .first { it.sourceUrl != jojiUrl }
            unstubbed.title.isNotBlank() shouldBe true
            unstubbed.eventDate shouldBe unstubbed.eventDate
            unstubbed.doorsTime shouldBe null
        }
}
