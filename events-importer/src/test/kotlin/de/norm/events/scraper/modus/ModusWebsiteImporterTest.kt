package de.norm.events.scraper.modus

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
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
 * Unit tests for [ModusWebsiteImporter].
 *
 * Only the three detail pages captured as fixtures are stubbed; every other detail fetch throws,
 * exercising the base class's degrade-to-overview fallback.
 */
class ModusWebsiteImporterTest {
    private lateinit var importer: ModusWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://modus-berlin.de/events"

    private val detailFixtures =
        mapOf(
            "240926-c4rl" to "c4rl",
            "160426-LunaSimao" to "luna-simao",
            "270826-SpreevomWeizenOpenAir-PoetrySlam-StandUpShow" to "poetry-slam"
        )

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/modus/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = ModusWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture("modus-overview.html"), sourceUrl),
                etag = "\"modus-etag\"",
                lastModified = "Sat, 01 Aug 2026 12:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        detailFixtures.forEach { (slug, fixture) ->
            val detailUrl = "https://modus-berlin.de/event/$slug"
            coEvery { htmlFetcher.fetchDocument(detailUrl) } returns
                Jsoup.parse(readFixture("modus-detail-$fixture.html"), detailUrl)
        }
    }

    @Test
    fun `importEvents extracts all events from fixture`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 17
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"modus-etag\""
            result.lastModified shouldBe "Sat, 01 Aug 2026 12:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns empty list for a page without event tiles`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><main class='container'></main></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `merges the detail page over the listing`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val c4rl = result.events.first { it.sourceId == "modus:240926-c4rl" }

            c4rl.eventDate shouldBe LocalDate.of(2026, 9, 24)
            c4rl.startTime shouldBe LocalTime.of(20, 0)
            c4rl.doorsTime shouldBe LocalTime.of(19, 30)
            c4rl.ticketUrl shouldBe "https://landstreicher-konzerte.de/konzerte/c4rl-b-26"
            c4rl.description!!.isNotBlank() shouldBe true
            c4rl.imageUrl shouldBe "https://modus-berlin.de/img/6a3e6c11430088.95263972.jpg"
        }

    @Test
    fun `keeps the rendered date of a postponed show through the merge`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val lunaSimao = result.events.first { it.sourceId == "modus:160426-LunaSimao" }
            lunaSimao.eventDate shouldBe LocalDate.of(2027, 4, 13)
            lunaSimao.title shouldBe "Luna Simao"
            lunaSimao.status shouldBe "POSTPONED"
        }

    @Test
    fun `falls back to listing data when a detail page cannot be fetched`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // Mia Morgan has no detail fixture, so its detail fetch throws.
            val miaMorgan = result.events.first { it.sourceId == "modus:101026-MiaMorgan" }
            miaMorgan.title shouldBe "Mia Morgan"
            miaMorgan.eventDate shouldBe LocalDate.of(2026, 10, 10)
            miaMorgan.artists.map { it.name } shouldBe listOf("Mia Morgan")
            // Detail-only fields stay empty rather than aborting the import.
            miaMorgan.startTime.shouldBeNull()
            miaMorgan.ticketUrl.shouldBeNull()
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.MODUS
    }
}
