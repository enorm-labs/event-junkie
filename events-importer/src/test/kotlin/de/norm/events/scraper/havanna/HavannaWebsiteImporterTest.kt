package de.norm.events.scraper.havanna

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit tests for [HavannaWebsiteImporter].
 *
 * The clock is pinned to Tuesday 2026-07-28 so the derived occurrence dates are stable — and so
 * the Wednesday page's live summer-break notice ("ab dem 01.07.2026") is in force, which is
 * exactly the case worth pinning: the venue's own announcement removes one of the three nights.
 */
class HavannaWebsiteImporterTest {
    private lateinit var importer: HavannaWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-28T10:00:00Z"), ZoneOffset.UTC)

    private val overviewUrl = "https://www.havanna-berlin.de/events"
    private val fridayUrl = "https://www.havanna-berlin.de/friday"

    private fun document(
        fixture: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/havanna/$fixture")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        importer = HavannaWebsiteImporter(htmlFetcher, clock)
        coEvery { htmlFetcher.fetchDocument(overviewUrl) } returns document("havanna-overview.html", overviewUrl)
        for (day in listOf("wednesday", "friday", "saturday")) {
            val url = "https://www.havanna-berlin.de/$day"
            coEvery { htmlFetcher.fetchDocument(url) } returns document("havanna-detail-$day.html", url)
        }
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.HAVANNA
    }

    @Test
    fun `expands the weekly nights into dated occurrences over the horizon`() =
        runTest {
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()

            // Friday and Saturday run for the full 8-week horizon; Wednesday is in its summer break.
            result.events shouldHaveSize 2 * HavannaWeeklyNight.OCCURRENCE_WEEKS
            result.events.map { it.sourceId }.distinct() shouldHaveSize result.events.size
            result.events.first().sourceId shouldBe "havanna:2026-07-31-friday"
            result.events.map { it.eventDate }.min() shouldBe LocalDate.of(2026, 7, 31)
            result.events.map { it.eventDate }.max() shouldBe LocalDate.of(2026, 9, 19)
        }

    @Test
    fun `drops the nights an announced closure covers`() =
        runTest {
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()

            // "WIR SIND AB DEM 01.07.2026 IN DER SOMMERPAUSE!" is announced on the Wednesday page only.
            result.events.filter { it.sourceId.endsWith("-wednesday") }.shouldBeEmpty()
            result.events
                .map { it.sourceUrl }
                .distinct() shouldContainExactly listOf(fridayUrl, "https://www.havanna-berlin.de/saturday")
        }

    @Test
    fun `never issues conditional requests and returns no cache headers`() =
        runTest {
            // The pages have not changed since 2016, so a 304 would freeze the rolling horizon and the
            // calendar would stop advancing — every run must re-fetch unconditionally.
            val result = importer.importEvents(overviewUrl, etag = "\"cached\"", lastModified = "Wed, 01 Jul 2026 00:00:00 GMT")
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 2 * HavannaWeeklyNight.OCCURRENCE_WEEKS
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()

            coVerify(exactly = 0) { htmlFetcher.fetch(any(), any(), any()) }
        }

    @Test
    fun `keeps the other nights when one night page cannot be fetched`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(fridayUrl) } throws HttpFetchException(500, fridayUrl)

            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // Only Saturday survives: Friday failed and Wednesday is on its summer break.
            result.events shouldHaveSize HavannaWeeklyNight.OCCURRENCE_WEEKS
            result.events.map { it.sourceUrl }.distinct() shouldContainExactly listOf("https://www.havanna-berlin.de/saturday")
        }

    @Test
    fun `returns an empty list for an overview page without night links`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(overviewUrl) } returns
                Jsoup.parse("<html><body><p>No events</p></body></html>", overviewUrl)

            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
