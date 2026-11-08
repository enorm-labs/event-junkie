package de.norm.events.scraper.heidegluehen

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [HeidegluehenWebsiteImporter].
 */
class HeidegluehenWebsiteImporterTest {
    private lateinit var importer: HeidegluehenWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val monthUrl = "https://heidegluehen.berlin/monatsvorschau/"
    private val weekUrl = "https://heidegluehen.berlin/aktuell/"

    @BeforeEach
    fun setUp() {
        importer = HeidegluehenWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(monthUrl, any(), any()) } returns
            FetchResult.Success(
                document = fixtureDocument("heidegluehen/heidegluehen-monatsvorschau.html", monthUrl),
                etag = "\"hg-etag\"",
                lastModified = "Thu, 30 Jul 2026 12:24:09 GMT"
            )
        stubFetcher(weekUrl to "heidegluehen/heidegluehen-aktuell-ohne-lineup.html", fetcher = htmlFetcher)
    }

    @Test
    fun `importEvents returns the month's parties`() =
        runTest {
            val result = importer.importEvents(monthUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 5
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(monthUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"hg-etag\""
            result.lastModified shouldBe "Thu, 30 Jul 2026 12:24:09 GMT"
        }

    @Test
    fun `importEvents returns NotModified without touching the week page`() =
        runTest {
            coEvery { htmlFetcher.fetch(monthUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(monthUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `importEvents returns empty list for a page without a programme`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><div class='fl-rich-text'><p>~~~</p></div></body></html>", monthUrl)
            coEvery { htmlFetcher.fetch(monthUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(monthUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `derives the week page from the configured month-page URL and fetches it once`() =
        runTest {
            importer.importEvents(monthUrl)
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(weekUrl) }
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `applies the week page's lineup to the party it belongs to`() =
        runTest {
            // A month page listing 6 June, so the archived week page matches one of its dates.
            val juneMonth =
                Jsoup.parse(
                    """
                    <html><body><div id="pagecontent"><div class="fl-rich-text">
                    <p>Samstag, 6. Juni 2026, 12 Uhr (bis Sonntag, 22 Uhr)<br><strong><mark>Heideglühen #18</mark></strong></p>
                    <p>~~~</p>
                    <p>Samstag, 13. Juni 2026, 12 Uhr (bis Sonntag, 6 Uhr)<br><strong><mark>Heideglühen #19</mark></strong></p>
                    </div></div></body></html>
                    """.trimIndent(),
                    monthUrl
                )
            coEvery { htmlFetcher.fetch(monthUrl, any(), any()) } returns
                FetchResult.Success(document = juneMonth, etag = null, lastModified = null)
            stubFetcher(weekUrl to "heidegluehen/heidegluehen-aktuell-mit-lineup.html", fetcher = htmlFetcher)

            val result = importer.importEvents(monthUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()

            val sixth = result.events.first { it.eventDate == LocalDate.of(2026, 6, 6) }
            sixth.artists shouldHaveSize 10
            sixth.artists.first().name shouldBe "Antal"
            sixth.imageUrl!! shouldEndWith "2600606_Heide18.gif"
            sixth.eventType shouldBe EventType.PARTY.name
            // The other date of the month keeps the month page's data.
            result.events
                .first { it.eventDate == LocalDate.of(2026, 6, 13) }
                .artists
                .shouldBeEmpty()
        }

    @Test
    fun `imports the month alone while no lineup is published`() =
        runTest {
            val result = importer.importEvents(monthUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.forEach { it.artists.shouldBeEmpty() }
            result.events.forEach { it.imageUrl!! shouldEndWith "2026_Monatsvorschau_August.gif" }
        }

    @Test
    fun `drops a lineup for a date the month no longer lists`() =
        runTest {
            // At a month boundary the week page can already show a party the month page has dropped.
            stubFetcher(weekUrl to "heidegluehen/heidegluehen-aktuell-mit-lineup.html", fetcher = htmlFetcher)

            val result = importer.importEvents(monthUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 5
            result.events.forEach { it.artists.shouldBeEmpty() }
        }

    @Test
    fun `imports the month when the week page cannot be fetched`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(weekUrl) } throws IllegalStateException("gone")

            val result = importer.importEvents(monthUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 5
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.HEIDEGLUEHEN
    }
}
