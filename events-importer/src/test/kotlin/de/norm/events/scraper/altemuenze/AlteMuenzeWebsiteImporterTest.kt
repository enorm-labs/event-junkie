package de.norm.events.scraper.altemuenze

import de.norm.events.common.BERLIN
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.dropPastEvents
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

class AlteMuenzeWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val clock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), BERLIN)
    private val importer = AlteMuenzeWebsiteImporter(apiClient, clock)
    private val sourceUrl = "https://www.alte-muenze-berlin.de/wp-json/tribe/events/v1/events"
    private val firstPageUrl = "$sourceUrl?start_date=2026-07-09&per_page=50"

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(firstPageUrl) } returns
            javaClass.classLoader
                .getResourceAsStream("scraper/altemuenze/altemuenze-events.json")!!
                .bufferedReader()
                .readText()
    }

    @Test
    fun `imports the one page the programme fills`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 29
            result.complete shouldBe true
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
            coVerify(exactly = 1) { apiClient.fetchJson(any()) }
        }

    @Test
    fun `asks from before today, so a run that opened last week stays and one that ended goes`() =
        runTest {
            coEvery { apiClient.fetchJson(firstPageUrl) } returns
                """
                {"events":[
                  {"url":"https://www.alte-muenze-berlin.de/event/gate-to-nancy-goerlach/","title":"GATE TO // Nancy Görlach",
                   "start_date":"2026-10-08 00:00:00","end_date":"2026-10-25 23:59:59","all_day":true,"categories":[{"name":"Ausstellung"}]},
                  {"url":"https://www.alte-muenze-berlin.de/event/gate-to-elma-riza/","title":"GATE TO // Elma Riza",
                   "start_date":"2026-11-03 00:00:00","end_date":"2026-11-30 23:59:59","all_day":true,"categories":[{"name":"Ausstellung"}]}
                ]}
                """.trimIndent()

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val kept = result.events.dropPastEvents(clock) {}
            kept.map { it.sourceId } shouldContainExactly listOf("alte_muenze:gate-to-elma-riza")
            kept.single().eventDate shouldBe LocalDate.of(2026, 11, 3)
            kept.single().endDate shouldBe LocalDate.of(2026, 11, 30)
        }

    @Test
    fun `fails the import when the first page fails`() =
        runTest {
            coEvery { apiClient.fetchJson(firstPageUrl) } throws HttpFetchException(503, firstPageUrl)

            shouldThrow<HttpFetchException> { importer.importEvents(sourceUrl) }
        }

    @Test
    fun `returns no events for an empty programme`() =
        runTest {
            coEvery { apiClient.fetchJson(firstPageUrl) } returns """{"events":[],"total":0,"total_pages":0}"""

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.ALTE_MUENZE
    }
}
