package de.norm.events.scraper.clubost

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [ClubOstWebsiteImporter].
 *
 * [HtmlFetcher] is mocked so the importer parses the saved fixtures without making real HTTP
 * requests. Two of the eight detail pages have fixtures of their own; the rest fall back to a
 * minimal stub, which is enough to exercise the merge in both directions.
 */
class ClubOstWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: ClubOstWebsiteImporter

    private val sourceUrl = "https://clubost.de/"
    private val blasphemyUrl = "https://clubost.de/event/231438/"
    private val newYearUrl = "https://clubost.de/event/239128/"

    @BeforeEach
    fun setUp() {
        importer = ClubOstWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(loadFixture("scraper/clubost/clubost-overview.html"), sourceUrl),
                etag = "\"ost-1\"",
                lastModified = "Tue, 04 Aug 2026 22:00:00 GMT"
            )

        // Default stub for the six detail pages without a fixture of their own: a page with no
        // content container, so the scraper returns null and the overview card is kept whole.
        coEvery { htmlFetcher.fetchDocument(any()) } returns
            Jsoup.parse("<html><body></body></html>", sourceUrl)
        coEvery { htmlFetcher.fetchDocument(blasphemyUrl) } returns
            Jsoup.parse(loadFixture("scraper/clubost/clubost-detail-blasphemy.html"), blasphemyUrl)
        coEvery { htmlFetcher.fetchDocument(newYearUrl) } returns
            Jsoup.parse(loadFixture("scraper/clubost/clubost-detail-nye-no-logo.html"), newYearUrl)
    }

    private fun loadFixture(path: String): String =
        javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.CLUB_OST
    }

    @Test
    fun `importEvents returns every event and propagates the conditional-request headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 8
            result.etag shouldBe "\"ost-1\""
            result.lastModified shouldBe "Tue, 04 Aug 2026 22:00:00 GMT"
        }

    @Test
    fun `importEvents takes the title from the detail page and everything else from the listing`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.first { it.sourceId == "club_ost:231438" }

            // Detail page wins on casing …
            event.title shouldBe "Blasphemy"
            // … and the listing keeps the fields the stub does not publish.
            event.imageUrl.shouldNotBeNull()
            event.ticketUrl shouldBe "https://de.ra.co/events/2391028"
            event.eventDate shouldBe LocalDate.of(2026, 8, 7)
            event.startTime shouldBe LocalTime.of(23, 0)
            event.eventType shouldBe "PARTY"
            event.description shouldBe null
            // The end is on the detail page alone and has to survive the merge (#1408).
            event.endDate shouldBe LocalDate.of(2026, 8, 8)
            event.endTime shouldBe LocalTime.of(8, 0)
        }

    @Test
    fun `importEvents keeps the listing title when the detail page cannot be parsed`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // Served the container-less default stub, so the overview card survives untouched.
            val event = result.events.first { it.sourceId == "club_ost:228340" }

            event.title shouldBe "ULTRASOZIAL"
            event.eventDate shouldBe LocalDate.of(2026, 8, 28)
        }

    @Test
    fun `importEvents falls back to the listing when a detail fetch throws`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("Network error")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 8
            val event = result.events.first { it.sourceId == "club_ost:231438" }
            event.title shouldBe "BLASPHEMY"
            event.ticketUrl shouldBe "https://de.ra.co/events/2391028"
        }

    @Test
    fun `importEvents does not let the detail page blank out the listing flyer`() =
        runTest {
            // The New Year's Eve detail page has no flyer, but its listing card does not
            // either — so this asserts the merge keeps the listing's null rather than the
            // ordering mattering. The Blasphemy pair covers the populated direction.
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.first { it.sourceId == "club_ost:239128" }

            event.title shouldBe "GEGEN X PRNCPTL X OST NYE 33H"
            event.imageUrl shouldBe null
            event.startTime shouldBe LocalTime.of(23, 55)
        }

    @Test
    fun `importEvents returns NotModified when the homepage is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns an empty list for a page with no cards`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body></body></html>", sourceUrl),
                    etag = null,
                    lastModified = null
                )

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }
}
