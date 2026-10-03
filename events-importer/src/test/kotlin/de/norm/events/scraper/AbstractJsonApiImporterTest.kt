package de.norm.events.scraper

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Unit tests for [AbstractJsonApiImporter], on a synthetic body: its event ids, comma-separated. */
class AbstractJsonApiImporterTest {
    private val apiClient: ApiClient = mockk()
    private val sourceUrl = "https://venue.example/api/events"

    private fun scrape(
        json: String,
        url: String
    ): List<ScrapedEvent> = json.split(',').map { ScrapedEvent(title = it, eventDate = LocalDate.of(2026, 10, 1), sourceUrl = url, sourceId = it) }

    private inner class Venue : AbstractJsonApiImporter(apiClient, "Venue", ::scrape) {
        override val eventSource: EventSource = EventSource.FESTSAAL

        override fun requestUrl(url: String): String = "$url?limit=100"
    }

    @Test
    fun `fetches the request URL, passes the configured URL to the scraper, and returns no validators`() =
        runTest {
            coEvery { apiClient.fetchJson("$sourceUrl?limit=100") } returns "a,b"

            val result = Venue().importEvents(sourceUrl, etag = "\"v1\"", lastModified = null).shouldBeInstanceOf<ImportResult.Success>()

            result.events.map { it.sourceId } shouldContainExactly listOf("a", "b")
            result.events.map { it.sourceUrl }.distinct() shouldContainExactly listOf(sourceUrl)
            result.etag.shouldBeNull()
            result.complete shouldBe true
        }
}
