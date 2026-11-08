package de.norm.events.scraper.zenner

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureText
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ZennerWebsiteImporter].
 *
 * Uses the saved page-data snapshot and a mocked [ApiClient] for deterministic,
 * offline-safe testing without real HTTP requests. The importer runs on the system clock,
 * so assertions here stay off the event *count* (which shrinks as the snapshot ages) —
 * [ZennerApiScraperTest] pins a fixed clock and covers the parsed data.
 */
class ZennerWebsiteImporterTest {
    private lateinit var importer: ZennerWebsiteImporter
    private val apiClient: ApiClient = mockk()
    private val programmeUrl = "https://zenner.berlin/programm"

    private val pageData = "zenner/zenner-page-data.json"

    @BeforeEach
    fun setUp() {
        importer = ZennerWebsiteImporter(apiClient)
        coEvery { apiClient.fetchJson(any()) } returns fixtureText(pageData)
    }

    @Test
    fun `importEvents parses events from the page-data artefact`() =
        runTest {
            val result = importer.importEvents(programmeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            // Every event links back to the venue's programme page — there are no detail pages.
            result.events.all { it.sourceUrl == programmeUrl } shouldBe true
            result.events.all { it.sourceId.startsWith("zenner:") } shouldBe true
        }

    @Test
    fun `importEvents derives the Gatsby page-data URL from the configured programme page`() =
        runTest {
            val requestUrl = slot<String>()
            coEvery { apiClient.fetchJson(capture(requestUrl)) } returns fixtureText(pageData)

            importer.importEvents(programmeUrl)

            coVerify { apiClient.fetchJson(any()) }
            requestUrl.captured shouldBe "https://zenner.berlin/page-data/programm/page-data.json"
        }

    @Test
    fun `importEvents derives the page-data URL from a trailing-slash programme page`() =
        runTest {
            val requestUrl = slot<String>()
            coEvery { apiClient.fetchJson(capture(requestUrl)) } returns fixtureText(pageData)

            importer.importEvents("https://zenner.berlin/programm/")

            requestUrl.captured shouldBe "https://zenner.berlin/page-data/programm/page-data.json"
        }

    @Test
    fun `importEvents derives the index page-data URL from the site root`() =
        runTest {
            val requestUrl = slot<String>()
            coEvery { apiClient.fetchJson(capture(requestUrl)) } returns fixtureText(pageData)

            importer.importEvents("https://zenner.berlin/")

            requestUrl.captured shouldBe "https://zenner.berlin/page-data/index/page-data.json"
        }

    @Test
    fun `importEvents reports no conditional-cache headers as the artefact tracks the site build`() =
        runTest {
            val result = importer.importEvents(programmeUrl, etag = "\"abc\"", lastModified = "Wed, 01 Jul 2026 00:00:00 GMT")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success for a payload without events`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns """{"result":{"data":{}}}"""

            val result = importer.importEvents(programmeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ZENNER
    }
}
