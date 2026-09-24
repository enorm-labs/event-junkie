package de.norm.events.scraper.monsterronsons

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [MonsterRonsonsWebsiteImporter].
 *
 * Uses saved snapshots of three listing pages and one night page, with [HtmlFetcher] mocked so no real HTTP
 * requests are made. The clock is pinned to the capture date (2026-08-06) so the listing's year-less
 * dates resolve deterministically.
 */
class MonsterRonsonsWebsiteImporterTest {
    private lateinit var importer: MonsterRonsonsWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-06T09:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val sourceUrl = "https://www.karaokemonster.de/events"

    @BeforeEach
    fun setUp() {
        importer = MonsterRonsonsWebsiteImporter(htmlFetcher, clock)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(loadFixture("monsterronsons-overview.html"), sourceUrl),
                etag = "\"events-1\"",
                lastModified = "Thu, 06 Aug 2026 06:00:00 GMT"
            )
        // Page 1 links page 2, whose snapshot links page 3; the last page's snapshot stands in for page 3.
        // Every night page returns the hosted snapshot; the opener is asserted on by name.
        coEvery { htmlFetcher.fetchDocument(any()) } answers {
            val url = firstArg<String>()
            val fixture =
                when (url) {
                    page2Url -> "monsterronsons-overview-page-2.html"
                    page3Url -> "monsterronsons-overview-page-last.html"
                    else -> "monsterronsons-detail-hosted.html"
                }
            Jsoup.parse(loadFixture(fixture), url)
        }
    }

    private val page2Url = "$sourceUrl?0ac6f618_page=2"
    private val page3Url = "$sourceUrl?0ac6f618_page=3"

    private fun loadFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/monsterronsons/$name")!!
            .bufferedReader()
            .readText()

    @Test
    fun `imports every listing page and propagates cache headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl, null, null).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize EVENTS_ON_ALL_PAGES
            result.events.last().eventDate shouldBe LocalDate.of(2026, 10, 31)
            result.etag shouldBe "\"events-1\""
            result.lastModified shouldBe "Thu, 06 Aug 2026 06:00:00 GMT"
        }

    @Test
    fun `enriches each night with its detail page`() =
        runTest {
            val result = importer.importEvents(sourceUrl, null, null).shouldBeInstanceOf<ImportResult.Success>()

            val opener = result.events.first { it.sourceId == "monster_ronsons:2026-08-06-sing-with-fauxpas-2" }
            opener.description.shouldNotBeNull() shouldContain "IVANKA TRAMP"
            opener.priceBoxOffice shouldBe BigDecimal("5")
            // The card's own fields survive the merge.
            opener.title shouldBe "SING WITH IVANKA TRAMP"
        }

    @Test
    fun `fetches one night page per distinct url`() =
        runTest {
            val result = importer.importEvents(sourceUrl, null, null).shouldBeInstanceOf<ImportResult.Success>()

            // Two listing pages, then one night page per distinct URL: `boxhopping-6` and
            // `sing-with-oozing-gloop-gutter-gucci` are recycled onto an August and an October night.
            // The closure card never reaches a fetch.
            coVerify(exactly = 2 + EVENTS_ON_ALL_PAGES - 2) { htmlFetcher.fetchDocument(any()) }
            result.events.map { it.sourceId }.distinct() shouldHaveSize EVENTS_ON_ALL_PAGES
            coVerify(exactly = 0) { htmlFetcher.fetchDocument("https://www.karaokemonster.de/posts/sorry-we-are-closed") }
        }

    @Test
    fun `keeps the first page when a later listing page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(page2Url) } throws HttpFetchException(503, page2Url)

            val result = importer.importEvents(sourceUrl, null, null).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 11
        }

    @Test
    fun `keeps the listing data when a night page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("connection reset")

            val result = importer.importEvents(sourceUrl, null, null).shouldBeInstanceOf<ImportResult.Success>()

            // The listing's later pages fail too, so only the first page's 11 nights remain.
            result.events shouldHaveSize 11
            val opener = result.events.first { it.sourceId == "monster_ronsons:2026-08-06-sing-with-fauxpas-2" }
            opener.title shouldBe "SING WITH IVANKA TRAMP"
            opener.description.shouldBeNull()
        }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl, "\"events-1\"", null).shouldBeInstanceOf<ImportResult.NotModified>()
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `returns no events for an empty listing`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body><div class='grid-container'></div></body></html>", sourceUrl),
                    etag = null,
                    lastModified = null
                )

            val result = importer.importEvents(sourceUrl, null, null).shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `reports its event source`() {
        importer.eventSource shouldBe EventSource.MONSTER_RONSONS
    }

    private companion object {
        /** 11 nights on page 1, 12 on page 2 and 3 on the last page. */
        const val EVENTS_ON_ALL_PAGES = 26
    }
}
