package de.norm.events.scraper.mbia

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate

/** Unit tests for [MbiaWebsiteImporter] with a mocked [ApiClient]. */
class MbiaWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val clock: Clock = Clock.fixed(LocalDate.of(2026, 10, 17).atStartOfDay(BERLIN).toInstant(), BERLIN)
    private val importer = MbiaWebsiteImporter(apiClient, clock)
    private val baseUrl = "https://www.m-bia.de/wp-json/wp/v2/posts"

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/mbia/mbia-posts.json")!!
            .bufferedReader()
            .readText()

    @Test
    fun `importEvents returns the upcoming nights from one request`() =
        runTest {
            val urls = mutableListOf<String>()
            coEvery { apiClient.fetchJson(capture(urls)) } returns fixture()

            val result = importer.importEvents(baseUrl, etag = "\"x\"", lastModified = "Sat, 17 Oct 2026 00:00:00 GMT")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 7
            result.complete shouldBe true
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
            urls shouldBe
                listOf(
                    "$baseUrl?per_page=100&page=1&_fields=id,date,link,title,content,_links.wp:featuredmedia,_embedded&_embed=wp:featuredmedia"
                )
        }

    @Test
    fun `importEvents returns an empty success for an empty listing`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns "[]"

            val result = importer.importEvents(baseUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.shouldBeEmpty()
        }

    @Test
    fun `eventSource is M_BIA`() {
        importer.eventSource shouldBe EventSource.M_BIA
    }
}
