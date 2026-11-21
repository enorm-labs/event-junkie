package de.norm.events.scraper.kulturhausspandau

import de.norm.events.common.BERLIN
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZonedDateTime

class KulturhausSpandauWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 12, 20, 9, 0, 0, 0, BERLIN).toInstant(), BERLIN)
    private val importer = KulturhausSpandauWebsiteImporter(apiClient, clock)
    private val sourceUrl = "https://kulturhaus-spandau.de/wp-json/eventmanager/v1/calendar/"
    private val requested = mutableListOf<String>()

    private fun answer(months: Map<String, String> = emptyMap()) =
        coEvery { apiClient.fetchJson(any()) } answers {
            val url = firstArg<String>()
            requested += url
            months[url.substringAfter('?')] ?: "[]"
        }

    @Test
    fun `asks for the current month and the next eleven, across the year`() =
        runTest {
            answer()

            importer.importEvents(sourceUrl)

            val months = listOf("month=12&year=2026") + (1..11).map { "month=$it&year=2027" }
            requested shouldContainExactly months.map { "$sourceUrl?$it" }
        }

    @Test
    fun `joins the months into one list of the house's events`() =
        runTest {
            answer(
                mapOf(
                    "month=12&year=2026" to fixtureText("kulturhausspandau/kulturhausspandau-calendar-2026-12.json"),
                    "month=1&year=2027" to fixtureText("kulturhausspandau/kulturhausspandau-calendar-2027-01.json")
                )
            )

            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.size shouldBe 27
            result.etag shouldBe null
        }

    @Test
    fun `returns no events when every month is empty`() =
        runTest {
            answer()

            importer
                .importEvents(sourceUrl)
                .shouldBeInstanceOf<ImportResult.Success>()
                .events
                .shouldBeEmpty()
        }

    @Test
    fun `fails the run when one month fails`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns "[]"
            coEvery { apiClient.fetchJson("$sourceUrl?month=3&year=2027") } throws IllegalStateException("HTTP 500")

            shouldThrow<IllegalStateException> { importer.importEvents(sourceUrl) }
        }

    @Test
    fun `is the Kulturhaus Spandau source and does not claim the whole programme`() {
        importer.eventSource shouldBe EventSource.KULTURHAUS_SPANDAU
        importer.listsWholeProgramme shouldBe false
    }
}
