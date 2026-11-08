package de.norm.events.scraper.drugstore

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureText
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [DrugstoreWebsiteImporter], with a mocked [ApiClient]. */
class DrugstoreWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = DrugstoreWebsiteImporter(apiClient)

    @Test
    fun `imports the nights and reads free entry and the bill from the description`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns fixtureText("radar/radar-group-drugstore.json")

            val result = importer.importEvents("https://radar.squat.net/api/1.2/search/events.json?facets%5Bgroup%5D%5B%5D=1680")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.title } shouldBe listOf("Konzert", "Eddy Playground", "Hardcore for Solidarity")
            // "Eintritt frei!" and "FREE ENTRY".
            result.events.filter { it.free }.map { it.title } shouldBe listOf("Konzert", "Hardcore for Solidarity")
            // Only the night with a "Featuring:" line bills anyone.
            result.events.map { it.artists.size } shouldBe listOf(0, 0, 6)
            result.events.last().genre shouldBe "Hardcore, Beatdown"
            // A path alias, not /node/<id>: the id comes from the row's key.
            result.events.first().sourceId shouldBe "drugstore:598022"
            result.events.first().sourceUrl shouldBe "https://radar.squat.net/en/event/berlin/drugstore/2026-10-03/konzert"
            importer.eventSource shouldBe EventSource.DRUGSTORE
        }

    private fun radarRow(
        start: String,
        end: String,
        body: String
    ) = """
        {"count": 1, "result": {"601334": {
          "title": "Nox A Carnival vol. 13 - Tag 1",
          "url": "https://radar.squat.net/en/node/601334",
          "date_time": [{"time_start": "$start", "time_end": "$end"}],
          "body": {"value": "$body"},
          "category": [{"name": "music/concert"}]
        }}}
        """.trimIndent()

    private suspend fun importOne(json: String): ScrapedEvent {
        coEvery { apiClient.fetchJson(any()) } returns json
        val result = importer.importEvents("https://radar.squat.net/api/1.2/search/events.json?facets%5Bgroup%5D%5B%5D=1680")
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events.single()
    }

    @Test
    fun `a night whose text labels both times starts at Start and opens at Doors`() =
        runTest {
            val event =
                importOne(
                    radarRow(
                        start = "2026-11-05T19:00:00+01:00",
                        end = "2026-11-05T19:00:00+01:00",
                        body = """<p>Doors: 7pm\u00a0</p>\n<p>Start: 8:30pm</p>\n"""
                    )
                )

            event.eventDate shouldBe LocalDate.of(2026, 11, 5)
            event.startTime shouldBe LocalTime.of(20, 30)
            event.doorsTime shouldBe LocalTime.of(19, 0)
        }

    @Test
    fun `a night with no label keeps the radar time`() =
        runTest {
            val event =
                importOne(
                    radarRow(
                        start = "2026-11-05T19:00:00+01:00",
                        end = "2026-11-05T19:00:00+01:00",
                        body = "<p>Free Entry</p>"
                    )
                )

            event.startTime shouldBe LocalTime.of(19, 0)
            event.doorsTime.shouldBeNull()
        }

    @Test
    fun `a row over several days keeps its dates and its end, and takes the labels for its first night`() =
        runTest {
            val event =
                importOne(
                    radarRow(
                        start = "2026-11-06T19:00:00+01:00",
                        end = "2026-11-08T02:00:00+01:00",
                        body = "<p>Doors: 7pm</p><p>Start: 8pm</p>"
                    )
                )

            event.eventDate shouldBe LocalDate.of(2026, 11, 6)
            event.startTime shouldBe LocalTime.of(20, 0)
            event.doorsTime shouldBe LocalTime.of(19, 0)
            event.endDate shouldBe LocalDate.of(2026, 11, 8)
            event.endTime shouldBe LocalTime.of(2, 0)
        }
}
