package de.norm.events.scraper.arcanoa

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
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
 * Unit tests for [ArcanoaWebsiteImporter].
 *
 * The clock is pinned to 2026-07-20 — before the fixture's earliest date (22.07.) — so
 * weekday-based year inference is deterministic.
 */
class ArcanoaWebsiteImporterTest {
    private lateinit var importer: ArcanoaWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC)
    private val sourceUrl = "https://www.ssi-media.com/arcanoa/veranst.htm"
    private val etag = "\"d393-6a5ff430-c081cd168460ad7b;;;\""
    private val lastModified = "Tue, 21 Jul 2026 22:35:28 GMT"

    @BeforeEach
    fun setUp() {
        importer = ArcanoaWebsiteImporter(htmlFetcher, clock)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document =
                    Jsoup.parse(
                        javaClass.classLoader.getResourceAsStream("scraper/arcanoa/arcanoa-overview.html")!!,
                        null,
                        sourceUrl
                    ),
                etag = etag,
                lastModified = lastModified
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ARCANOA
    }

    @Test
    fun `importEvents parses the programme and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 54
            result.events.first().eventDate shouldBe LocalDate.of(2026, 7, 22)
            result.events.first().title shouldBe "Mittelalter-Irish Folk"
            result.etag shouldBe etag
            result.lastModified shouldBe lastModified
        }

    @Test
    fun `importEvents returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, etag, lastModified) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl, etag, lastModified) shouldBe ImportResult.NotModified
        }

    @Test
    fun `importEvents returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body></body></html>", sourceUrl),
                    etag = null,
                    lastModified = null
                )

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }
}
