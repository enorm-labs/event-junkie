package de.norm.events.scraper.admiralspalast

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AdmiralspalastWebsiteImporter]: the listing is discovery only, the category pages
 * type the productions, and each production page contributes one event per performance.
 */
class AdmiralspalastWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: AdmiralspalastWebsiteImporter

    private val host = "https://www.admiralspalast.theater"
    private val listingUrl = "$host/veranstaltungsuebersicht.html"
    private val comedyUrl = "$host/veranstaltungsuebersicht/eventkategorie/comedy.html"
    private val abbaUrl = "$host/veranstaltung/abba-gold-the-concert-show-emotion.html"
    private val antigoneUrl = "$host/veranstaltung/bodo-wartke-antigone.html"

    private fun document(
        fixture: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/admiralspalast/$fixture")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        importer = AdmiralspalastWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
            FetchResult.Success(
                document = document("admiralspalast-overview.html", listingUrl),
                etag = "\"admiralspalast-etag\"",
                lastModified = "Sat, 01 Aug 2026 06:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(comedyUrl) } returns document("admiralspalast-genre-comedy.html", comedyUrl)
        coEvery { htmlFetcher.fetchDocument(abbaUrl) } returns document("admiralspalast-detail-abba.html", abbaUrl)
        coEvery { htmlFetcher.fetchDocument(antigoneUrl) } returns document("admiralspalast-detail-single.html", antigoneUrl)

        val stubbed = setOf(comedyUrl, abbaUrl, antigoneUrl)
        coEvery { htmlFetcher.fetchDocument(match { it !in stubbed }) } returns Jsoup.parse("<html><body></body></html>", host)
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ADMIRALSPALAST
    }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(listingUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `collects one event per performance and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(listingUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // Two of the 100 productions are stubbed with a real page: a two-night run and a single night.
            result.events.map { it.sourceId } shouldContain "admiralspalast:abba-gold-the-concert-show-emotion-2027-01-25-1930"
            result.events.map { it.sourceId } shouldContain "admiralspalast:abba-gold-the-concert-show-emotion-2027-01-26-1930"
            result.events.count { it.sourceUrl == abbaUrl } shouldBe 2
            result.etag shouldBe "\"admiralspalast-etag\""
        }

    @Test
    fun `types a production from the category page that lists it`() =
        runTest {
            val result = importer.importEvents(listingUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // Antigone appears on the Comedy filter page, which is the only place a category exists.
            val antigone = result.events.first { it.sourceUrl == antigoneUrl }
            // The category types the production; it is not stored as a genre.
            antigone.genre shouldBe null
            antigone.eventType shouldBe EventType.SHOW.name
        }

    @Test
    fun `an unreachable production costs only its own events`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(abbaUrl) } throws RuntimeException("503")
            val result = importer.importEvents(listingUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.none { it.sourceUrl == abbaUrl } shouldBe true
            result.events.any { it.sourceUrl == antigoneUrl } shouldBe true
        }
}
