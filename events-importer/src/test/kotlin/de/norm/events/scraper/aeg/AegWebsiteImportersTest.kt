package de.norm.events.scraper.aeg

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [UberArenaWebsiteImporter] and [UberEatsMusicHallWebsiteImporter], the two thin
 * venue importers over the shared AEG parser pair.
 *
 * Only one detail page per venue is stubbed; every other detail fetch throws, exercising the base
 * class's degrade-to-overview fallback.
 */
class AegWebsiteImportersTest {
    private lateinit var arenaImporter: UberArenaWebsiteImporter
    private lateinit var musicHallImporter: UberEatsMusicHallWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private val arenaUrl = "https://www.uber-arena.de/events/all"
    private val arenaDetailUrl = "https://www.uber-arena.de/events/detail/diljit-dosanjh/2026-08-21-2000"
    private val musicHallUrl = "https://www.uber-eats-music-hall.de/events/all"
    private val musicHallDetailUrl = "https://www.uber-eats-music-hall.de/events/detail/jony/2026-09-15-1900"

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/aeg/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        arenaImporter = UberArenaWebsiteImporter(htmlFetcher)
        musicHallImporter = UberEatsMusicHallWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(arenaUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture("uberarena-overview.html"), arenaUrl),
                etag = "\"ua-etag\"",
                lastModified = "Mon, 03 Aug 2026 09:00:00 GMT"
            )
        coEvery { htmlFetcher.fetch(musicHallUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture("ubereatsmusichall-overview.html"), musicHallUrl),
                etag = "\"uemh-etag\"",
                lastModified = "Mon, 03 Aug 2026 09:30:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        coEvery { htmlFetcher.fetchDocument(arenaDetailUrl) } returns
            Jsoup.parse(readFixture("uberarena-detail-diljit-dosanjh.html"), arenaDetailUrl)
        coEvery { htmlFetcher.fetchDocument(musicHallDetailUrl) } returns
            Jsoup.parse(readFixture("ubereatsmusichall-detail-jony.html"), musicHallDetailUrl)
    }

    @Test
    fun `importEvents keeps the arena's non-sport programme`() =
        runTest {
            val result = arenaImporter.importEvents(arenaUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 88
        }

    @Test
    fun `importEvents keeps the whole music hall programme`() =
        runTest {
            val result = musicHallImporter.importEvents(musicHallUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 66
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = arenaImporter.importEvents(arenaUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"ua-etag\""
            result.lastModified shouldBe "Mon, 03 Aug 2026 09:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(arenaUrl, any(), any()) } returns FetchResult.NotModified

            val result = arenaImporter.importEvents(arenaUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns empty list for a page without rows`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><div id='content'></div></body></html>", arenaUrl)
            coEvery { htmlFetcher.fetch(arenaUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = arenaImporter.importEvents(arenaUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `merges the arena detail page while keeping the listing's cleaner title`() =
        runTest {
            val result = arenaImporter.importEvents(arenaUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val diljit = result.events.first { it.sourceId == "uber_arena:diljit-dosanjh/2026-08-21-2000" }

            // Listing wins: the detail heading says "Diljit Dosanjh in der Uber Arena".
            diljit.title shouldBe "Diljit Dosanjh"
            diljit.eventDate shouldBe LocalDate.of(2026, 8, 21)
            diljit.startTime shouldBe LocalTime.of(20, 0)
            diljit.pricePresale shouldBe BigDecimal("83.50")
            diljit.eventType shouldBe EventType.CONCERT.name
            diljit.artists.map { it.name } shouldBe listOf("Diljit Dosanjh")
            // Detail-only.
            diljit.doorsTime shouldBe LocalTime.of(18, 30)
            diljit.description!!.isNotBlank() shouldBe true
            diljit.ticketUrl!! shouldStartWith "https://queue-de.axs.com/"
        }

    @Test
    fun `merges the music hall detail page the same way`() =
        runTest {
            val result = musicHallImporter.importEvents(musicHallUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val jony = result.events.first { it.sourceId == "uber_eats_music_hall:jony/2026-09-15-1900" }

            // Listing wins: the detail heading says "JONY live in der Uber Eats Music Hall".
            jony.title shouldBe "JONY"
            jony.eventDate shouldBe LocalDate.of(2026, 9, 15)
            jony.startTime shouldBe LocalTime.of(19, 0)
            jony.eventType shouldBe EventType.CONCERT.name
            // Detail-only.
            jony.doorsTime shouldBe LocalTime.of(17, 30)
            jony.description!!.isNotBlank() shouldBe true
            jony.ticketUrl shouldBe "https://queue-de.axs.com/?c=axsde&e=4231211618132917"
        }

    @Test
    fun `keeps the listing's cancellation through the merge`() =
        runTest {
            // The detail page states no status, so the listing's "ABGESAGT:" prefix must survive.
            val result = musicHallImporter.importEvents(musicHallUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events
                .filter { it.status == EventStatus.CANCELLED.name }
                .map { it.title } shouldBe listOf("Arena Rave", "Ryan Adams")
        }

    @Test
    fun `falls back to listing data when a detail page cannot be fetched`() =
        runTest {
            val result = arenaImporter.importEvents(arenaUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val other = result.events.first { it.sourceId != "uber_arena:diljit-dosanjh/2026-08-21-2000" }
            other.title.isNotBlank() shouldBe true
            // Detail-only fields stay empty rather than aborting the import.
            other.doorsTime.shouldBeNull()
            other.description.shouldBeNull()
            other.ticketUrl.shouldBeNull()
        }

    @Test
    fun `eventSource matches expected enum value`() {
        arenaImporter.eventSource shouldBe EventSource.UBER_ARENA
        musicHallImporter.eventSource shouldBe EventSource.UBER_EATS_MUSIC_HALL
    }
}
