package de.norm.events.scraper.donau115

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Unit tests for [Donau115WebsiteImporter], with the saved database behind a mocked [ApiClient]. */
class Donau115WebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val importer = Donau115WebsiteImporter(apiClient, clock)
    private val homepage = "https://www.donau115.de/"
    private val requestUrl = slot<String>()

    private val fixture: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/donau115/donau115-events.json")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(capture(requestUrl)) } returns fixture
    }

    @Test
    fun `importEvents reads the Firebase database the homepage renders`() =
        runTest {
            val result = importer.importEvents(homepage)

            requestUrl.captured shouldBe "https://shifts-a77a1-default-rtdb.europe-west1.firebasedatabase.app/events.json"
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 42
            result.events.all { it.sourceId.startsWith("donau115:") && it.sourceUrl == homepage } shouldBe true
        }

    @Test
    fun `importEvents ignores the validators, because the database sends none`() =
        runTest {
            val result = importer.importEvents(homepage, etag = "\"abc\"", lastModified = "Wed, 01 Jul 2026 00:00:00 GMT")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success for an empty database`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns "{}"

            val result = importer.importEvents(homepage)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.DONAU115
        importer.listsWholeProgramme shouldBe true
    }
}
