package de.norm.events.scraper.matrix

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate

/**
 * Unit tests for [MatrixWebsiteImporter]. A mocked [HtmlFetcher] serves the home page and the night
 * pages captured on 2026-10-08, so the whole run is offline.
 */
class MatrixWebsiteImporterTest {
    private lateinit var importer: MatrixWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private val entryUrl = "https://www.matrix-berlin.de/party-in-berlin/"
    private val formats = listOf("social", "icon", "matrix", "legacy", "reboot", "switch", "velvet")

    private fun nightUrl(format: String) = "https://www.matrix-berlin.de/de/night/$format"

    @BeforeEach
    fun setUp() {
        importer = MatrixWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetchDocument(entryUrl) } returns fixtureDocument("matrix/matrix-home-de.html", "https://www.matrix-berlin.de/de")
        formats.forEach { format ->
            stubFetcher(nightUrl(format) to "matrix/matrix-night-$format-de.html", fetcher = htmlFetcher)
        }
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.MATRIX
        importer.listsWholeProgramme shouldBe true
    }

    @Test
    fun `importEvents reads one night from each format page the home page links`() =
        runTest {
            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.eventDate } shouldContainExactly (8..14).map { LocalDate.of(2026, 10, it) }
            result.events.all { it.eventType == EventType.PARTY.name } shouldBe true
            result.complete shouldBe true
            // The site sends no validators, so the run stores none.
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents keeps the other nights and reports itself incomplete when a night page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(nightUrl("icon")) } throws IOException("connection reset")

            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 6
            result.complete shouldBe false
        }

    @Test
    fun `importEvents stays complete when a format page shows no next date`() =
        runTest {
            val undated =
                fixtureDocument("matrix/matrix-night-icon-de.html", nightUrl("icon")).apply { select(".eyebrow").first()?.text("Bald wieder") }
            coEvery { htmlFetcher.fetchDocument(nightUrl("icon")) } returns undated

            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 6
            result.complete shouldBe true
        }

    @Test
    fun `importEvents reports an incomplete run when the home page links no night`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns Jsoup.parse("<html><body></body></html>", "https://www.matrix-berlin.de/de")

            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
            result.complete shouldBe false
        }
}
