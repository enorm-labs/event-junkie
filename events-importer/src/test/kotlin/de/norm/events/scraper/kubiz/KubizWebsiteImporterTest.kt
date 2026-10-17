package de.norm.events.scraper.kubiz

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class KubizWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val clock = Clock.fixed(Instant.parse("2026-02-20T09:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val importer = KubizWebsiteImporter(apiClient, clock)
    private val baseUrl = "https://www.kubiz-wallenberg.de/?rest_route=/wp/v2/posts"

    /** 180 days before the clock's 2026-02-20. */
    private fun pageUrl(page: Int) = "$baseUrl&per_page=100&page=$page&after=2025-08-24T00:00:00&_fields=id,date,link,title,content"

    private val fixture: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/kubiz/kubiz-posts.json")!!
            .bufferedReader()
            .readText()

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.KUBIZ
    }

    @Test
    fun `asks for the posts of the publish window and stops after a short page`() =
        runTest {
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns fixture

            val result = importer.importEvents(baseUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 10
            result.complete shouldBe true
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
            coVerify(exactly = 0) { apiClient.fetchJson(pageUrl(2)) }
        }

    @Test
    fun `returns no events for an empty listing`() =
        runTest {
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns "[]"

            importer
                .importEvents(baseUrl)
                .shouldBeInstanceOf<ImportResult.Success>()
                .events
                .shouldBeEmpty()
        }
}
