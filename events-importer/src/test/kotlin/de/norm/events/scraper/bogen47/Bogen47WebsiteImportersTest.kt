package de.norm.events.scraper.bogen47

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [LarkWebsiteImporter] and [FitzroyWebsiteImporter].
 *
 * Uses the saved WordPress REST fixtures and a mocked [ApiClient], so no real HTTP happens. The
 * clock is pinned to the capture date because the importer drops past-dated events.
 */
class Bogen47WebsiteImportersTest {
    private lateinit var importer: LarkWebsiteImporter
    private val apiClient: ApiClient = mockk()
    private val clock: Clock = Clock.fixed(LocalDate.of(2026, 8, 1).atStartOfDay(BERLIN).toInstant(), BERLIN)
    private val baseUrl = "https://larkberlin.com/wp-json/wp/v2/event"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/bogen47/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = LarkWebsiteImporter(apiClient, clock)
        coEvery { apiClient.fetchJson(match { "/media?" in it }) } returns fixture("lark-media.json")
        coEvery { apiClient.fetchJson(match { "/media?" !in it }) } returns fixture("lark-events.json")
    }

    @Test
    fun `importEvents returns the upcoming programme with posters resolved`() =
        runTest {
            val result = importer.importEvents(baseUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 20
            result.events.all { it.imageUrl != null } shouldBe true
            result.events
                .first { it.title == "Ben Morgan" }
                .imageUrl
                .shouldNotBeNull() shouldStartWith
                "https://larkberlin.com/wp-content/uploads/"
        }

    @Test
    fun `importEvents requests the listing with the parser's field projection`() =
        runTest {
            val urls = mutableListOf<String>()
            coEvery { apiClient.fetchJson(capture(urls)) } answers {
                if ("/media?" in firstArg<String>()) fixture("lark-media.json") else fixture("lark-events.json")
            }

            importer.importEvents(baseUrl)

            urls.first() shouldBe "$baseUrl?per_page=100&page=1&_fields=id,link,title,date,acf,featured_media"
        }

    @Test
    fun `importEvents resolves every poster in one batched media request`() =
        runTest {
            val urls = mutableListOf<String>()
            coEvery { apiClient.fetchJson(capture(urls)) } answers {
                if ("/media?" in firstArg<String>()) fixture("lark-media.json") else fixture("lark-events.json")
            }

            importer.importEvents(baseUrl)

            // One listing page (its oldest event is past, so paging stops) plus one media lookup.
            urls shouldHaveSize 2
            val mediaUrl = urls.last()
            mediaUrl shouldStartWith "https://larkberlin.com/wp-json/wp/v2/media?include="
            mediaUrl shouldContain "_fields=id,source_url"
        }

    @Test
    fun `importEvents stops paging once a page reaches the past`() =
        runTest {
            var listingCalls = 0
            coEvery { apiClient.fetchJson(any()) } answers {
                if ("/media?" in firstArg<String>()) {
                    fixture("lark-media.json")
                } else {
                    listingCalls++
                    fixture("lark-events.json")
                }
            }

            importer.importEvents(baseUrl)

            // The captured page is full (100 posts) but reaches back to 2026-02-19, so page 2 is never asked for.
            listingCalls shouldBe 1
        }

    @Test
    fun `importEvents keeps the first page when a later page fails`() =
        runTest {
            // Before the captured page's oldest date, so the full page 1 asks for page 2.
            val early = LarkWebsiteImporter(apiClient, Clock.fixed(LocalDate.of(2026, 1, 1).atStartOfDay(BERLIN).toInstant(), BERLIN))
            coEvery { apiClient.fetchJson(match { "page=2" in it }) } throws RuntimeException("connection reset")

            val result = early.importEvents(baseUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.shouldNotBeEmpty()
            result.complete shouldBe false
        }

    @Test
    fun `importEvents keeps the events when the media lookup fails to parse`() =
        runTest {
            coEvery { apiClient.fetchJson(match { "/media?" in it }) } returns """{"code":"rest_forbidden"}"""

            val result = importer.importEvents(baseUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 20
            result.events.all { it.imageUrl == null } shouldBe true
        }

    @Test
    fun `importEvents reports no conditional-cache headers`() =
        runTest {
            val result = importer.importEvents(baseUrl, etag = "\"abc\"", lastModified = "Sat, 01 Aug 2026 00:00:00 GMT")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success for an empty listing`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns "[]"

            val result = importer.importEvents(baseUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `every scraped event carries the source-id prefix`() =
        runTest {
            val result = importer.importEvents(baseUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.all { it.sourceId.startsWith(EventSource.LARK.sourceIdPrefix) } shouldBe true
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.LARK
    }

    @Test
    fun `Fitzroy imports its upcoming nights with posters from its own host`() =
        runTest {
            val fitzroyUrl = "https://fitzroy-berlin.de/wp-json/wp/v2/event"
            val fitzroy = FitzroyWebsiteImporter(apiClient, Clock.fixed(LocalDate.of(2026, 10, 1).atStartOfDay(BERLIN).toInstant(), BERLIN))
            val urls = mutableListOf<String>()
            coEvery { apiClient.fetchJson(capture(urls)) } answers {
                if ("/media?" in firstArg<String>()) fixture("fitzroy-media.json") else fixture("fitzroy-events.json")
            }

            val result = fitzroy.importEvents(fitzroyUrl).shouldBeInstanceOf<ImportResult.Success>()

            fitzroy.eventSource shouldBe EventSource.FITZROY
            result.events shouldHaveSize 7
            result.events.all { it.sourceId.startsWith(EventSource.FITZROY.sourceIdPrefix) } shouldBe true
            // The media endpoint does not return Hüft & Beinbruch's attachment 2480, so that night keeps no poster.
            result.events.count { it.imageUrl?.startsWith("https://fitzroy-berlin.de/wp-content/uploads/") == true } shouldBe 6
            result.events
                .first { it.title == "Hüft & Beinbruch" }
                .imageUrl
                .shouldBeNull()
            urls.last() shouldStartWith "https://fitzroy-berlin.de/wp-json/wp/v2/media?include="
        }

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
