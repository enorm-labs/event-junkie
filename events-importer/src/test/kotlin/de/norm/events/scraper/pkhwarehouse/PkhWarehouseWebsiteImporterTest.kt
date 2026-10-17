package de.norm.events.scraper.pkhwarehouse

import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
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

/** Unit tests for [PkhWarehouseWebsiteImporter]. */
class PkhWarehouseWebsiteImporterTest {
    private lateinit var importer: PkhWarehouseWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.pkh.events/"

    private fun fixture() =
        javaClass.classLoader
            .getResourceAsStream("scraper/pkhwarehouse/pkhwarehouse-overview.html")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = PkhWarehouseWebsiteImporter(htmlFetcher, Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), BERLIN))
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture(), sourceUrl),
                etag = null,
                lastModified = "Fri, 09 Oct 2026 10:22:43 GMT"
            )
    }

    @Test
    fun `importEvents reads the whole programme from the home page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 14
            result.complete shouldBe true
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe null
            result.lastModified shouldBe "Fri, 09 Oct 2026 10:22:43 GMT"
        }

    @Test
    fun `importEvents returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `importEvents returns no events for an empty page`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body></body></html>", sourceUrl),
                    etag = null,
                    lastModified = null
                )

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `the listing is the whole programme and nothing past it is fetched`() {
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe false
    }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.PKH_WAREHOUSE
    }
}
