package de.norm.events.scraper.heimathafen

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [HeimathafenWebsiteImporter].
 *
 * Uses a mocked [ApiClient] so the paging contract is exercised offline. That contract is the
 * point of this importer: upcoming performances are scattered across the whole archive, so it must
 * keep requesting pages until one comes back short — and must never request the page *after* the
 * last, which WordPress answers with a 400.
 */
class HeimathafenWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = HeimathafenWebsiteImporter(apiClient)
    private val baseUrl = "https://heimathafen-neukoelln.de/wp-json/wp/v2/events"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/heimathafen/$name")!!
            .bufferedReader()
            .readText()

    private fun pageUrl(page: Int) = "$baseUrl?per_page=100&page=$page&_fields=id,link,title,excerpt,content,acf,class_list,featured_images"

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.HEIMATHAFEN
    }

    @Test
    fun `walks pages until one comes back short`() =
        runTest {
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns fixture("heimathafen-events-page1.json")
            coEvery { apiClient.fetchJson(pageUrl(2)) } returns fixture("heimathafen-events-page5.json")

            val result = importer.importEvents(baseUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()

            // Page 1 is full (100 posts) so paging continues; page 2 is short (8 posts) so it stops.
            coVerify(exactly = 1) { apiClient.fetchJson(pageUrl(1)) }
            coVerify(exactly = 1) { apiClient.fetchJson(pageUrl(2)) }
            coVerify(exactly = 0) { apiClient.fetchJson(pageUrl(3)) }
        }

    @Test
    fun `stops after a single short page`() =
        runTest {
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns fixture("heimathafen-events-page5.json")

            importer.importEvents(baseUrl).shouldBeInstanceOf<ImportResult.Success>()
            coVerify(exactly = 0) { apiClient.fetchJson(pageUrl(2)) }
        }

    @Test
    fun `returns events with no conditional headers`() =
        runTest {
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns fixture("heimathafen-events-page5.json")

            val result = importer.importEvents(baseUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.isNotEmpty() shouldBe true
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `keeps the first page when a later page fails`() =
        runTest {
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns fixture("heimathafen-events-page1.json")
            coEvery { apiClient.fetchJson(pageUrl(2)) } returns "[]"
            val firstPageOnly = importer.importEvents(baseUrl).shouldBeInstanceOf<ImportResult.Success>()
            coEvery { apiClient.fetchJson(pageUrl(2)) } throws RuntimeException("connection reset")

            val result = importer.importEvents(baseUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.shouldNotBeEmpty()
            result.events shouldBe firstPageOnly.events
            result.complete shouldBe false
        }

    @Test
    fun `deduplicates events repeated across pages`() =
        runTest {
            // A post shifting between pages between two requests would otherwise be imported twice.
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns fixture("heimathafen-events-page1.json")
            coEvery { apiClient.fetchJson(pageUrl(2)) } returns fixture("heimathafen-events-page1.json")
            coEvery { apiClient.fetchJson(pageUrl(3)) } returns "[]"

            val result = importer.importEvents(baseUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.sourceId }.distinct() shouldHaveSize result.events.size
        }

    @Test
    fun `returns no events for an empty first page`() =
        runTest {
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns "[]"

            val result = importer.importEvents(baseUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
