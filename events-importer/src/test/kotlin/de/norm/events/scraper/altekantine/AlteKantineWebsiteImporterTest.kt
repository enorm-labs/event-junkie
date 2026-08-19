package de.norm.events.scraper.altekantine

import de.norm.events.event.EventType
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
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [AlteKantineWebsiteImporter].
 *
 * Focuses on the overview ↔ detail merge: the detail page supplies the kind, price, description,
 * image and DJ, while the overview supplies the subtitle and stands in entirely (with its own date
 * and start time) whenever a detail page fails to fetch. A fixed clock keeps year inference
 * deterministic.
 */
class AlteKantineWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: AlteKantineWebsiteImporter

    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-01T10:00:00Z"), ZoneOffset.UTC)
    private val overviewUrl = "https://alte-kantine.eu/"
    private val partyUrl = "https://alte-kantine.eu/?p=12371"
    private val quizUrl = "https://alte-kantine.eu/?p=12281"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/altekantine/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = AlteKantineWebsiteImporter(htmlFetcher, clock)

        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("altekantine-overview.html"), overviewUrl),
                etag = "\"altekantine-etag\"",
                lastModified = "Sun, 12 Jul 2026 03:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(partyUrl) } returns
            Jsoup.parse(fixture("altekantine-detail-party.html"), partyUrl)
        coEvery { htmlFetcher.fetchDocument(quizUrl) } returns
            Jsoup.parse(fixture("altekantine-detail-quiz.html"), quizUrl)

        // Every other event's detail page is unavailable, so the importer degrades to
        // overview data (which already carries a real date, so nothing is dropped).
        val stubbed = setOf(partyUrl, quizUrl)
        coEvery { htmlFetcher.fetchDocument(match { it !in stubbed }) } returns
            Jsoup.parse("<html><body></body></html>", overviewUrl)
    }

    private fun events(result: ImportResult): List<ScrapedEvent> {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    private fun event(
        result: ImportResult,
        postId: String
    ): ScrapedEvent = events(result).first { it.sourceId == "alte_kantine:$postId" }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ALTE_KANTINE
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
            result.events shouldHaveSize 10
            result.etag shouldBe "\"altekantine-etag\""
            result.lastModified shouldBe "Sun, 12 Jul 2026 03:00:00 GMT"
        }

    @Test
    fun `merges detail kind, price, image, description and DJ with the overview subtitle`() =
        runTest {
            val party = event(importer.importEvents(overviewUrl), "12371")
            // From the detail page:
            party.eventType shouldBe EventType.PARTY.name
            party.priceBoxOffice shouldBe BigDecimal("4")
            party.imageUrl.shouldNotBeNull()
            party.description.shouldNotBeNull()
            party.artists shouldContainExactly listOf(ScrapedArtist(name = "Funky Henning", role = "DJ"))
            // The subtitle comes only from the overview and survives the merge:
            party.subtitle shouldBe "Funky Henning"
            // Shared fields resolve consistently across both pages:
            party.eventDate shouldBe LocalDate.of(2026, 7, 23)
            party.startTime shouldBe LocalTime.of(22, 0)
        }

    @Test
    fun `degrades to overview data when a detail page is unavailable`() =
        runTest {
            // Hungry Monday's detail page is not stubbed, so the merge falls back to the overview,
            // which still carries a real date and start time.
            val hungryMonday = event(importer.importEvents(overviewUrl), "12331")
            hungryMonday.title shouldBe "Hungry Monday"
            hungryMonday.eventDate shouldBe LocalDate.of(2026, 7, 13)
            hungryMonday.startTime shouldBe LocalTime.of(22, 0)
        }

    @Test
    fun `returns no events for an empty overview page`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body></body></html>", overviewUrl),
                    etag = null,
                    lastModified = null
                )
            events(importer.importEvents(overviewUrl)).shouldHaveSize(0)
        }
}
