package de.norm.events.scraper.littlestagebar

import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

/** Unit tests for [LittleStageBarWebsiteImporter]. */
class LittleStageBarWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://littlestageclubneukoelln.wordpress.com/feed/"
    private val importer = LittleStageBarWebsiteImporter(htmlFetcher, Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), BERLIN))

    private fun fixture() =
        javaClass.classLoader
            .getResourceAsStream("scraper/littlestagebar/littlestagebar-feed.xml")
            .let { requireNotNull(it) }
            .bufferedReader()
            .readText()

    @Test
    fun `importEvents reads the dated posts from the feed`() =
        runTest {
            coEvery { htmlFetcher.fetchHtml(sourceUrl) } returns fixture()

            val result = importer.importEvents(sourceUrl, "\"etag\"", "Fri, 16 Oct 2026 00:18:12 GMT")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 5
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns no events for an empty feed`() =
        runTest {
            coEvery { htmlFetcher.fetchHtml(sourceUrl) } returns "<rss><channel></channel></rss>"

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.LITTLE_STAGE_BAR
    }
}
