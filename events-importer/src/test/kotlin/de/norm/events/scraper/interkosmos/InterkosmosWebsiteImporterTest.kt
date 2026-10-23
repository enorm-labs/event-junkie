package de.norm.events.scraper.interkosmos

import de.norm.events.common.BERLIN
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZonedDateTime

class InterkosmosWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, BERLIN).toInstant(), BERLIN)
    private val importer = InterkosmosWebsiteImporter(apiClient, clock)
    private val url = "https://www.interkosmos.cc/wp-json/wp/v2/events"

    @Test
    fun `asks for a year of one-off events and for the rules`() =
        runTest {
            coEvery {
                apiClient.fetchJson("$url?dateRangeStart=2026-10-23&dateRangeEnd=2027-10-23&orderby=_ik_date&order=asc&per_page=100")
            } returns interkosmosFixture("interkosmos-events.json")
            coEvery { apiClient.fetchJson("$url?repetition=true&per_page=100") } returns interkosmosFixture("interkosmos-rules.json")

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 19
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.INTERKOSMOS
        importer.listsWholeProgramme shouldBe false
    }
}
