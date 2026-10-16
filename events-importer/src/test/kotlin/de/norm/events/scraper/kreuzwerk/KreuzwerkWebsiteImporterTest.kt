package de.norm.events.scraper.kreuzwerk

import de.norm.events.event.EventType
import de.norm.events.scraper.AcceptedLimitations
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
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
 * Unit tests for [KreuzwerkWebsiteImporter], through the Ritter Butzke scrapers it reuses.
 *
 * Three of the six detail pages are captured; the other fetches throw, so those nights keep
 * only the listing's fields.
 */
class KreuzwerkWebsiteImporterTest {
    private lateinit var importer: KreuzwerkWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://kreuzwerk.club/events"

    private val detailFixtures =
        mapOf(
            "231026-PowerDanceClub" to "powerdanceclub",
            "061126-BodyLanguage" to "bodylanguage",
            "141126-tracey" to "tracey"
        )

    private fun readFixture(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream("scraper/kreuzwerk/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = KreuzwerkWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture("kreuzwerk-overview.html"), sourceUrl),
                etag = "\"kw-etag\"",
                lastModified = "Fri, 16 Oct 2026 08:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        detailFixtures.forEach { (slug, fixture) ->
            val detailUrl = "https://kreuzwerk.club/event/$slug"
            coEvery { htmlFetcher.fetchDocument(detailUrl) } returns
                Jsoup.parse(readFixture("kreuzwerk-detail-$fixture.html"), detailUrl)
        }
    }

    private suspend fun events(): List<ScrapedEvent> {
        val result = importer.importEvents(sourceUrl)
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    private suspend fun event(slug: String): ScrapedEvent = events().first { it.sourceId == "kreuzwerk:$slug" }

    @Test
    fun `reads every card of the listing with its rendered date and paragraph title`() =
        runTest {
            val events = events()

            events shouldHaveSize 6
            events.map { it.eventDate } shouldBe
                listOf(
                    LocalDate.of(2026, 10, 16),
                    LocalDate.of(2026, 10, 23),
                    LocalDate.of(2026, 10, 31),
                    LocalDate.of(2026, 11, 6),
                    LocalDate.of(2026, 11, 13),
                    LocalDate.of(2026, 11, 14)
                )
            events.map { it.title } shouldBe listOf("LECKEN", "Power BUTT Club", "Hoe__mies", "Body Language", "PULS x TILT", "tracey")
            events.map { it.eventType }.toSet() shouldBe setOf(EventType.PARTY.name)
        }

    @Test
    fun `keys every night on the slug under the kreuzwerk prefix`() =
        runTest {
            val lecken = event("161026-LECKEN")

            lecken.sourceUrl shouldBe "https://kreuzwerk.club/event/161026-LECKEN"
            lecken.imageUrl shouldBe "https://kreuzwerk.club/img/6aa9325fcc04f1.67543929.jpg"
            // No captured detail page: the listing's fields survive the failed fetch.
            lecken.startTime.shouldBeNull()
        }

    @Test
    fun `merges start time, lineup and blurb from the detail page`() =
        runTest {
            val night = event("231026-PowerDanceClub")

            night.title shouldBe "Power BUTT Club"
            night.startTime shouldBe LocalTime.of(23, 59)
            night.doorsTime.shouldBeNull()
            night.artists.map { it.name } shouldBe
                listOf("Banana Moon", "Massimiliano Pagliara", "BYRON YEATES", "Cashu", "Chris Cruse", "Majdolen", "Stathis")
            night.artists.map { it.role }.toSet() shouldBe setOf("DJ")
            night.description shouldBe
                "Power Dance Club and BUTT Magazine revive their love affair in Berlin. Aw!\n" +
                "Join us for a very special 14 hour function, featuring a new BUTT-curated chill out space: the XXX KINO\n" +
                "Expect the usual sweaty dance floors ;)"
            night.ticketUrl.shouldBeNull()
        }

    @Test
    fun `reads the pretix shop url and leaves an empty blurb out`() =
        runTest {
            val night = event("061126-BodyLanguage")

            // The rendered row says "131bpm LIVE DEBUT"; the JSON-LD performer is the act alone.
            night.artists.map { it.name } shouldBe
                listOf("131bpm", "ābnamā", "Camilla Rae", "Christian Tokyo", "Miss Parker", "Stella Zekri", "Manolo.")

            night.startTime shouldBe LocalTime.of(22, 0)
            night.ticketUrl shouldBe "https://kw.ticketlodge.de/061126-BodyLanguage/"
            night.description.shouldBeNull()
        }

    @Test
    fun `reads a resident advisor ticket button and a night without a lineup`() =
        runTest {
            val night = event("141126-tracey")

            night.ticketUrl!! shouldStartWith "https://de.ra.co/events/"
            night.artists.shouldBeEmpty()
            night.description shouldBe "See you at your favourite party’s new favourite party!"
        }

    @Test
    fun `stores every night with the club's Techno, House house genre`() =
        runTest {
            AcceptedLimitations.withHouseGenre(EventSource.KREUZWERK, events()).map { it.genre }.distinct() shouldBe listOf("Techno, House")
        }

    @Test
    fun `propagates the conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"kw-etag\""
            result.lastModified shouldBe "Fri, 16 Oct 2026 08:00:00 GMT"
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for a page without cards`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            events().shouldBeEmpty()
        }

    @Test
    fun `names its own event source`() {
        importer.eventSource shouldBe EventSource.KREUZWERK
    }
}
