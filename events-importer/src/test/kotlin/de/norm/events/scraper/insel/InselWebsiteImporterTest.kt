package de.norm.events.scraper.insel

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureText
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [InselWebsiteImporter].
 *
 * Uses static JSON fixtures and a mocked [ApiClient] for deterministic, offline-safe testing. What
 * matters here is the artefact discovery: the importer must read the page's `staticQueryHashes`,
 * try each candidate in the order Gatsby wrote them, and stop at the first one that is the
 * programme query — skipping the sibling query that publishes the same collection projected down to
 * bare dates.
 */
class InselWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = InselWebsiteImporter(apiClient)
    private val programmeUrl = "https://www.inselberlin.de/"
    private val pageDataUrl = "https://www.inselberlin.de/page-data/index/page-data.json"

    private fun sqUrl(hash: String) = "https://www.inselberlin.de/page-data/sq/d/$hash.json"

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.INSEL
    }

    @Test
    fun `derives the page-data url from the programme page and imports the programme artefact`() =
        runTest {
            // The real page-data lists six static queries; the programme is the second of them.
            coEvery { apiClient.fetchJson(pageDataUrl) } returns fixtureText("insel/insel-page-data.json")
            coEvery { apiClient.fetchJson(sqUrl("2282490745")) } returns """{"data":{"datoCmsOpeningtime":{}}}"""
            coEvery { apiClient.fetchJson(sqUrl("3497155224")) } returns fixtureText("insel/insel-events.json")

            val result = importer.importEvents(programmeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.all { it.sourceUrl == programmeUrl } shouldBe true
            result.events.all { it.sourceId.startsWith("insel:") } shouldBe true
            // Gatsby rebuilds the artefacts on every deploy, so their validators track the build.
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
            coVerify(exactly = 1) { apiClient.fetchJson(pageDataUrl) }
            coVerify(exactly = 1) { apiClient.fetchJson(sqUrl("2282490745")) }
            coVerify(exactly = 1) { apiClient.fetchJson(sqUrl("3497155224")) }
        }

    @Test
    fun `stops probing once the programme artefact is found`() =
        runTest {
            coEvery { apiClient.fetchJson(pageDataUrl) } returns
                """{"staticQueryHashes":["111","3497155224","999"]}"""
            coEvery { apiClient.fetchJson(sqUrl("111")) } returns fixtureText("insel/insel-dates-only.json")
            coEvery { apiClient.fetchJson(sqUrl("3497155224")) } returns fixtureText("insel/insel-events.json")

            importer.importEvents(programmeUrl)

            coVerify(exactly = 1) { apiClient.fetchJson(sqUrl("111")) }
            coVerify(exactly = 0) { apiClient.fetchJson(sqUrl("999")) }
        }

    @Test
    fun `returns no events when none of the static queries carries the programme`() =
        runTest {
            coEvery { apiClient.fetchJson(pageDataUrl) } returns """{"staticQueryHashes":["111","222"]}"""
            coEvery { apiClient.fetchJson(sqUrl("111")) } returns """{"data":{"datoCmsFooter":{}}}"""
            coEvery { apiClient.fetchJson(sqUrl("222")) } returns fixtureText("insel/insel-dates-only.json")

            val result = importer.importEvents(programmeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `returns no events when the page-data lists no static queries`() =
        runTest {
            coEvery { apiClient.fetchJson(pageDataUrl) } returns """{"path":"/"}"""

            val result = importer.importEvents(programmeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `returns no events when the page-data body is unparseable`() =
        runTest {
            coEvery { apiClient.fetchJson(pageDataUrl) } returns "<html>404</html>"

            val result = importer.importEvents(programmeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}
