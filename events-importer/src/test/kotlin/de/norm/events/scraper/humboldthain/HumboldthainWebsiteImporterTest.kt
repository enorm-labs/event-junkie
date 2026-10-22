package de.norm.events.scraper.humboldthain

import de.norm.events.common.BERLIN
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
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
import java.time.Clock
import java.time.LocalDate

/**
 * Unit tests for [HumboldthainWebsiteImporter].
 *
 * Uses a saved JSON fixture and a mocked [ApiClient] for deterministic, offline-safe testing
 * without real HTTP requests. The clock is pinned to the day the fixture was captured so the
 * recurrence horizon is reproducible; dropping past-dated events remains the persistence layer's
 * concern (`EventUpsertService`).
 */
class HumboldthainWebsiteImporterTest {
    private lateinit var importer: HumboldthainWebsiteImporter
    private val apiClient: ApiClient = mockk()
    private val clock: Clock = Clock.fixed(LocalDate.of(2026, 8, 1).atStartOfDay(BERLIN).toInstant(), BERLIN)
    private val bootUrl = "https://core.service.elfsight.com/p/boot/?w=ee9a7876-11b1-4268-88e7-84b4d7397c75"

    private val fixtureJson: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/humboldthain/humboldthain-api.json")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = HumboldthainWebsiteImporter(apiClient, clock)
        coEvery { apiClient.fetchJson(any()) } returns fixtureJson
    }

    @Test
    fun `importEvents parses all events from the widget response`() =
        runTest {
            val result = importer.importEvents(bootUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 80
        }

    @Test
    fun `importEvents fetches the configured boot URL verbatim`() =
        runTest {
            val requestUrl = slot<String>()
            coEvery { apiClient.fetchJson(capture(requestUrl)) } returns fixtureJson

            importer.importEvents(bootUrl)

            coVerify { apiClient.fetchJson(any()) }
            requestUrl.captured shouldBe bootUrl
        }

    @Test
    fun `importEvents reports no conditional-cache headers so the recurrence horizon keeps advancing`() =
        runTest {
            val result = importer.importEvents(bootUrl, etag = "W/\"2ab15\"", lastModified = "Sat, 01 Aug 2026 14:37:02 GMT")
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success for a payload without events`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns """{"status":1,"data":{"widgets":{}}}"""

            val result = importer.importEvents(bootUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `every scraped event carries the source-id prefix`() =
        runTest {
            val result = importer.importEvents(bootUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.all { it.sourceId.startsWith(EventSource.HUMBOLDTHAIN.sourceIdPrefix) } shouldBe true
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.HUMBOLDTHAIN
    }
}
