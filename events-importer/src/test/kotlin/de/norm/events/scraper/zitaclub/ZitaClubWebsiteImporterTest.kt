package de.norm.events.scraper.zitaclub

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for [ZitaClubWebsiteImporter], with the saved feed and pages behind mocked fetchers. */
class ZitaClubWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = ZitaClubWebsiteImporter(apiClient, htmlFetcher)

    private fun resource(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/zitaclub/$name")!!
            .bufferedReader()
            .readText()

    private fun page(
        name: String,
        url: String
    ): Document = Jsoup.parse(resource(name), url)

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(FEED) } returns resource("zitaclub-events.ics")
        coEvery { htmlFetcher.fetchDocument(ROCK) } returns page("zitaclub-event-rock-at-zita.html", ROCK)
        coEvery { htmlFetcher.fetchDocument(DANSE) } returns page("zitaclub-event-danse-macabre.html", DANSE)
        coEvery { htmlFetcher.fetchDocument(DANSE_2) } throws IllegalStateException("page down")
    }

    @Test
    fun `importEvents reads the feed, enriches each night from its page and flags a failed page`() =
        runTest {
            val result = importer.importEvents(HOME)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 3
            result.events.all { it.sourceId.startsWith("zita_club:") } shouldBe true
            val rock = result.events.single { it.sourceUrl == ROCK }
            rock.genre shouldBe "Alternativ Rock, Classic Rock, Metal, Rock"
            rock.detailUnavailable shouldBe false
            result.events.single { it.sourceUrl == DANSE_2 }.detailUnavailable shouldBe true
        }

    @Test
    fun `importEvents ignores the validators and returns none`() =
        runTest {
            val result = importer.importEvents(HOME, etag = "\"abc\"", lastModified = "Wed, 01 Jul 2026 00:00:00 GMT")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success for a calendar without events`() =
        runTest {
            coEvery { apiClient.fetchJson(FEED) } returns "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"

            val result = importer.importEvents(HOME)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ZITA_CLUB
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe true
    }

    private companion object {
        const val HOME = "https://zita-club.de/"
        const val FEED = "https://zita-club.de/events.ics?scope=future"
        const val ROCK = "https://zita-club.de/events/rock-at-zita-16/"
        const val DANSE = "https://zita-club.de/events/danse-macabre-a-tribute-to-bruno-kramm/"
        const val DANSE_2 = "https://zita-club.de/events/danse-macabre-a-tribute-to-bruno-kramm-2/"
    }
}
