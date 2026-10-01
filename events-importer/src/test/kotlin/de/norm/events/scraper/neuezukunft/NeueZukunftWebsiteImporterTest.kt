package de.norm.events.scraper.neuezukunft

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
import java.time.ZoneId

/**
 * Unit tests for [NeueZukunftWebsiteImporter].
 *
 * Uses a saved JSON fixture and a mocked [ApiClient] for deterministic,
 * offline-safe testing without real HTTP requests. The clock is pinned so the recurrence
 * horizon is reproducible; dropping past-dated events is the persistence layer's concern
 * (`EventUpsertService`).
 */
class NeueZukunftWebsiteImporterTest {
    private lateinit var importer: NeueZukunftWebsiteImporter
    private val apiClient: ApiClient = mockk()
    private val berlin = ZoneId.of("Europe/Berlin")
    private val clock: Clock = Clock.fixed(LocalDate.of(2026, 10, 1).atStartOfDay(berlin).toInstant(), berlin)
    private val bootUrl = "https://core.service.elfsight.com/p/boot/?w=e767cbbe-0026-4173-a511-5aaa105ed563"

    private val fixtureJson: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/neuezukunft/neuezukunft-api.json")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = NeueZukunftWebsiteImporter(apiClient, clock)
        coEvery { apiClient.fetchJson(any()) } returns fixtureJson
    }

    @Test
    fun `importEvents parses all events from the widget response`() =
        runTest {
            val result = importer.importEvents(bootUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // 40 one-off entries plus 6 occurrences of each of the 4 monthly series.
            result.events shouldHaveSize 64
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
    fun `importEvents reports no conditional-cache headers as the Elfsight API sends none`() =
        runTest {
            val result = importer.importEvents(bootUrl)
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
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.NEUE_ZUKUNFT
    }
}
