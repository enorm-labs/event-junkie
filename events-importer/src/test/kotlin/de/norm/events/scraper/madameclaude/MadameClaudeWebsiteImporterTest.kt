package de.norm.events.scraper.madameclaude

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MadameClaudeWebsiteImporter].
 *
 * Uses a saved JSON fixture and a mocked [ApiClient] for deterministic,
 * offline-safe testing without real HTTP requests.
 */
class MadameClaudeWebsiteImporterTest {
    private lateinit var importer: MadameClaudeWebsiteImporter
    private val apiClient: ApiClient = mockk()
    private val apiBaseUrl = "https://madameclaude.de/wp-json/wp/v2/event"

    private val fixtureJson: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/madameclaude/madameclaude-events.json")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = MadameClaudeWebsiteImporter(apiClient)
        coEvery { apiClient.fetchJson(any()) } returns fixtureJson
    }

    @Test
    fun `importEvents parses all events from the API response`() =
        runTest {
            val result = importer.importEvents(apiBaseUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 10
        }

    @Test
    fun `importEvents builds an upcoming-only, image-embedded query from the configured base URL`() =
        runTest {
            val requestUrl = slot<String>()
            coEvery { apiClient.fetchJson(capture(requestUrl)) } returns fixtureJson

            importer.importEvents(apiBaseUrl)

            coVerify { apiClient.fetchJson(any()) }
            requestUrl.captured shouldStartWith "$apiBaseUrl?"
            requestUrl.captured shouldContain "per_page=100"
            requestUrl.captured shouldContain "orderby=date"
            requestUrl.captured shouldContain "order=asc"
            requestUrl.captured shouldContain "after="
            requestUrl.captured shouldContain "_embed=wp:featuredmedia"
        }

    @Test
    fun `importEvents reports no conditional-cache headers`() =
        runTest {
            val result = importer.importEvents(apiBaseUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success for an empty array payload`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns "[]"

            val result = importer.importEvents(apiBaseUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.MADAME_CLAUDE
    }

    @Test
    fun `importEvents reads the next page when the first is full, so a long programme is not cut`() =
        runTest {
            val fullPage = (1..100).joinToString(",", "[", "]") { "{}" }
            coEvery { apiClient.fetchJson(match { it.endsWith("&page=1") }) } returns fullPage
            coEvery { apiClient.fetchJson(match { it.endsWith("&page=2") }) } returns fixtureJson

            val result = importer.importEvents(apiBaseUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.isNotEmpty() shouldBe true
            result.complete shouldBe true
            coVerify(exactly = 1) { apiClient.fetchJson(match { it.endsWith("&page=2") }) }
        }
}
