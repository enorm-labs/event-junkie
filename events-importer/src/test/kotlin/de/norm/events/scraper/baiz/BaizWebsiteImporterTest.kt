package de.norm.events.scraper.baiz

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Tests for [BaizWebsiteImporter], on the radar group response saved on 2026-10-16. */
class BaizWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = BaizWebsiteImporter(apiClient)
    private val url = "https://radar.squat.net/api/1.2/search/events.json?facets%5Bgroup%5D%5B%5D=1609&limit=500"

    private suspend fun importFixture(): List<ScrapedEvent> {
        coEvery { apiClient.fetchJson(any()) } returns
            javaClass.classLoader
                .getResourceAsStream("scraper/radar/radar-group-baiz.json")!!
                .bufferedReader()
                .readText()
        val result = importer.importEvents(url)
        result.shouldBeInstanceOf<ImportResult.Success>()
        result.complete shouldBe true
        return result.events
    }

    @Test
    fun `keeps the stage nights and drops the tenants' advice hour and the chess afternoon`() =
        runTest {
            val events = importFixture()

            // 54 rows: 10 advice hours and 1 chess afternoon are dropped; 33 Sunday Reformbühnen stay.
            events shouldHaveSize 43
            events.count { it.title == "Reformbühne Heim und Welt" } shouldBe 33
            events.none { "Mieterberatung" in it.title || "Schach" in it.title } shouldBe true
        }

    @Test
    fun `types each night by its radar category, then by the format word in its title`() =
        runTest {
            val types = importFixture().associate { it.title to it.eventType }

            types["Polyphonie"] shouldBe "CONCERT"
            types["Roda de choro"] shouldBe "CONCERT"
            types["Theater mit Tallercito"] shouldBe "SHOW"
            types["„Mehr Bier, Mann!“ 1976 – Die DDR in der Krise"] shouldBe "READING"
            types["Reformbühne Heim und Welt"] shouldBe "READING"
            types["SCHMUTZTITELLESUNG: Robert Mießner und Julian Weber"] shouldBe "READING"
            types["Lesung Eric Ahrens"] shouldBe "READING"
            types["Kunstbühne ENGEL IM HAUS,"] shouldBe "READING"
            types["Flamenco es diferente"] shouldBe "SHOW"
            types["Wello Rauschs Karaoke Gala"] shouldBe "PARTY"
        }

    @Test
    fun `maps a row's fields`() =
        runTest {
            val karaoke = importFixture().single { it.title == "Wello Rauschs Karaoke Gala" }

            karaoke.eventDate shouldBe LocalDate.of(2026, 10, 17)
            karaoke.startTime shouldBe LocalTime.of(21, 0)
            karaoke.endTime.shouldBeNull()
            karaoke.sourceUrl shouldBe "https://radar.squat.net/en/node/597745"
            karaoke.sourceId shouldBe "baiz:597745"
            karaoke.description!! shouldStartWith "Singen, performen"
            karaoke.free shouldBe true
            karaoke.artists.shouldBeEmpty()
            importer.eventSource shouldBe EventSource.BAIZ
        }

    @Test
    fun `reads an empty group as no events`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns """{"result":[],"count":0}"""

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
