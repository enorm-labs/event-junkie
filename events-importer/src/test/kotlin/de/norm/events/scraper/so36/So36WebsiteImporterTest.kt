package de.norm.events.scraper.so36

import de.norm.events.event.EventType
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
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Unit tests for [So36WebsiteImporter].
 *
 * Two detail pages (a concert and a party) are stubbed explicitly; every other discovered event
 * degrades to its overview data.
 */
class So36WebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: So36WebsiteImporter
    private val sourceUrl = "https://www.so36.com/tickets"
    private val concertUrl =
        "https://www.so36.com/produkte/95201-tickets-poison-ruin-so36-berlin-am-09-07-2026"
    private val partyUrl =
        "https://www.so36.com/produkte/97683-tickets-last-night-so36-berlin-am-03-07-2026"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/so36/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = So36WebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("so36-overview.html"), sourceUrl),
                etag = "\"so36-etag\"",
                lastModified = "Fri, 03 Jul 2026 21:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(concertUrl) } returns
            Jsoup.parse(fixture("so36-detail-concert.html"), concertUrl)
        coEvery { htmlFetcher.fetchDocument(partyUrl) } returns
            Jsoup.parse(fixture("so36-detail-party.html"), partyUrl)

        // Every other detail page returns an empty document, so the importer
        // degrades to the overview data (title + date from the product link).
        coEvery {
            htmlFetcher.fetchDocument(match { it != concertUrl && it != partyUrl })
        } returns Jsoup.parse(EMPTY_DETAIL, sourceUrl)
    }

    private fun events(result: ImportResult): List<ScrapedEvent> {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.SO36
    }

    @Test
    fun `imports all discovered events and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 108
            result.etag shouldBe "\"so36-etag\""
            result.lastModified shouldBe "Fri, 03 Jul 2026 21:00:00 GMT"
        }

    @Test
    fun `enriches a concert with detail-page data`() =
        runTest {
            val concert = events(importer.importEvents(sourceUrl)).first { it.sourceId == "so36:95201" }
            concert.eventType shouldBe EventType.CONCERT.name
            concert.pricePresale shouldBe BigDecimal("22.0")
            concert.ticketUrl shouldBe "https://www.greyzone-tickets.de/produkte/1271"
            concert.artists shouldHaveSize 3
            concert.description.shouldNotBeNull()
            concert.eventDate shouldBe LocalDate.of(2026, 7, 9)
        }

    @Test
    fun `enriches a party with detail-page data and no lineup`() =
        runTest {
            val party = events(importer.importEvents(sourceUrl)).first { it.sourceId == "so36:97683" }
            party.eventType shouldBe EventType.PARTY.name
            party.artists shouldHaveSize 0
            party.title shouldBe "LAST NIGHT"
        }

    @Test
    fun `degrades to overview data when a detail page is empty`() =
        runTest {
            // KOMA AMED's detail page is stubbed empty, so only the overview
            // title and date survive; the type stays unset (defaults later to OTHER).
            val komaAmed = events(importer.importEvents(sourceUrl)).first { it.sourceId == "so36:93680" }
            komaAmed.title shouldBe "KOMA AMED"
            komaAmed.eventDate shouldBe LocalDate.of(2026, 7, 4)
            komaAmed.eventType shouldBe null
        }

    @Test
    fun `returns NotModified when the overview page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    private companion object {
        private const val EMPTY_DETAIL = "<html><body><div>empty</div></body></html>"
    }
}
