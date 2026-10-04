package de.norm.events.scraper.berghain

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
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
import java.time.ZoneOffset

/**
 * Unit tests for [BerghainWebsiteImporter].
 *
 * The overview fetch returns the saved `/de/program/` snapshot. Detail fetches are
 * stubbed: one event's detail page (BUTOH Batorū / id 80835) returns the full
 * snapshot so the merge can be asserted, while every other detail fetch returns an
 * empty document, forcing those events to fall back to overview data. The clock is
 * pinned before every fixture event so none are dropped by the past-event cutoff.
 */
class BerghainWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var importer: BerghainWebsiteImporter

    private val sourceUrl = "https://www.berghain.berlin/de/program/"
    private val butohDetailUrl = "https://www.berghain.berlin/de/event/80835/"

    private fun loadFixture(path: String): String =
        javaClass.classLoader
            .getResourceAsStream(path)!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = BerghainWebsiteImporter(htmlFetcher, clock)

        val overviewDoc = Jsoup.parse(loadFixture("scraper/berghain/berghain-overview.html"), sourceUrl)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = overviewDoc, etag = null, lastModified = null)

        // Default: an empty detail document — the scraper returns null, so the event keeps overview data.
        coEvery { htmlFetcher.fetchDocument(any()) } returns Jsoup.parse("<html><body></body></html>", sourceUrl)
        // BUTOH Batorū: the full detail snapshot, so image/prices/ticket are merged in.
        coEvery { htmlFetcher.fetchDocument(butohDetailUrl) } returns
            Jsoup.parse(loadFixture("scraper/berghain/berghain-detail-full.html"), butohDetailUrl)
    }

    @Test
    fun `importEvents parses all events from the overview`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 21
        }

    @Test
    fun `importEvents merges detail data over overview and keeps the overview lineup`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.first { it.sourceId == "berghain:80835" }

            // From the detail page:
            event.imageUrl!!.shouldStartWith("https://cdn.berghain.berlin/")
            event.pricePresale shouldBe BigDecimal("20.00")
            event.priceBoxOffice shouldBe BigDecimal("22.00")
            event.ticketUrl shouldBe "https://ticketingv2.berghain.de/event/butoh"
            event.eventType shouldBe EventType.PARTY.name
            event.genre shouldBe "Techno"
            // From the overview page (the detail scraper does not parse the lineup):
            event.artists shouldHaveSize 4
            event.artists.first().name shouldBe "Hurricane Alexander"
            event.artists.first().stage shouldBe "Berghain"
        }

    @Test
    fun `importEvents falls back to overview data when the detail page is empty`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.first { it.title == "Sound Metaphors" }

            event.imageUrl.shouldBeNull()
            event.ticketUrl.shouldBeNull()
            event.eventType shouldBe EventType.PARTY.name
            // Genre falls back to the overview's floor-derived value when the detail page is empty.
            event.genre shouldBe "House, Techno"
            event.artists.shouldNotBeEmpty()
        }

    @Test
    fun `importEvents propagates ETag and Last-Modified from the fetch`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse(loadFixture("scraper/berghain/berghain-overview.html"), sourceUrl),
                    etag = "etag-123",
                    lastModified = "Wed, 01 Jul 2026 00:00:00 GMT"
                )

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "etag-123"
            result.lastModified shouldBe "Wed, 01 Jul 2026 00:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns an empty list for a page without events`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body><div class='content'></div></body></html>", sourceUrl),
                    etag = null,
                    lastModified = null
                )

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `importEvents follows the Mehr pages until one comes back empty`() =
        runTest {
            val page2 = "$sourceUrl?page=2"
            val page3 = "$sourceUrl?page=3"
            coEvery { htmlFetcher.fetchDocument(page2) } returns Jsoup.parse(loadFixture("scraper/berghain/berghain-overview-page-2.html"), page2)
            coEvery { htmlFetcher.fetchDocument(page3) } returns Jsoup.parse(loadFixture("scraper/berghain/berghain-overview-page-3.html"), page3)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()

            // 31 listed; the Kantine night 82476 on page 2 is the Kantine source's.
            result.events shouldHaveSize 30
            result.events.map { it.sourceId } shouldContain "berghain:80748"
            result.events.map { it.sourceId } shouldNotContain "berghain:82476"
            result.complete shouldBe true
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(page3) }
            coVerify(exactly = 0) { htmlFetcher.fetchDocument("$sourceUrl?page=4") }
        }

    @Test
    fun `importEvents reads Kantine as one page, since it has no Mehr button`() =
        runTest {
            val kantineUrl = "https://www.berghain.berlin/de/program/kantine-am-berghain/"
            coEvery { htmlFetcher.fetch(kantineUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse(loadFixture("scraper/berghain/kantine-overview.html"), kantineUrl), etag = null, lastModified = null)

            importer.importEvents(kantineUrl).shouldBeInstanceOf<ImportResult.Success>()

            coVerify(exactly = 0) { htmlFetcher.fetchDocument(match { "page=" in it }) }
        }

    // The main programme's second page also lists Kantine nights, and both sources took the row in turn (#2557).
    @Test
    fun `a Kantine night on both programme pages belongs to the Kantine source alone`() =
        runTest {
            val kantineUrl = "https://www.berghain.berlin/de/program/kantine-am-berghain/"
            val kantineNight =
                """<a href="/de/event/82476/"><p>Mittwoch <span class="font-bold">28.10.2026</span> tür 19:00 beginn 20:00</p>""" +
                    """<h2>Sound Metaphors Opening Concerts</h2><h3>Kantine am Berghain</h3>""" +
                    """<h4><span class="font-bold"><span>Lenge</span></span></h4></a>"""
            val clubNight =
                """<a href="/de/event/80850/"><p>Samstag <span class="font-bold">31.10.2026</span> beginn 23:59</p>""" +
                    """<h2>Klubnacht</h2><h3>Berghain</h3><h4><span class="font-bold"><span>Marcel Dettmann</span></span></h4></a>"""
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body>$kantineNight$clubNight</body></html>", sourceUrl), etag = null, lastModified = null)
            coEvery { htmlFetcher.fetch(kantineUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body>$kantineNight</body></html>", kantineUrl), etag = null, lastModified = null)
            // A detail page with no floor block cannot tell the Kantine from the club, so the programme's floor decides.
            val detailUrl = "https://www.berghain.berlin/de/event/82476/"
            coEvery { htmlFetcher.fetchDocument(detailUrl) } returns
                Jsoup.parse(
                    """<html><body><main><h1>Sound Metaphors Opening Concerts</h1><p><span class="font-bold">28.10.2026</span></p></main></body></html>""",
                    detailUrl
                )

            val club = importer.importEvents(sourceUrl)
            val kantine = importer.importEvents(kantineUrl)

            club.shouldBeInstanceOf<ImportResult.Success>()
            kantine.shouldBeInstanceOf<ImportResult.Success>()
            club.events.map { it.sourceId } shouldBe listOf("berghain:80850")
            kantine.events.map { it.sourceId } shouldBe listOf("kantine_am_berghain:82476")
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(detailUrl) }
        }

    @Test
    fun `importEvents attaches the running order's set times to the overview lineup`() =
        runTest {
            // The 26.09 Klubnacht as the programme bills it, trimmed to four slots: one act per floor
            // with a time, a Live act, a back-to-back, and one act the running order does not name.
            val overview =
                """
                <html><body>
                <a href="/de/event/80744/">
                  <p>Samstag <span class="font-bold">26.09.2026</span> beginn 23:59</p>
                  <h2>Klubnacht</h2>
                  <h3>Berghain</h3>
                  <h4><span><span>Joline Scheffler</span>,</span>
                      <span><span>Colin Benders</span><span class="uppercase">Live</span></span></h4>
                  <h3>Panorama Bar</h3>
                  <h4><span><span>nd_baumecker</span><span class="uppercase">b2b</span><span>Jorkes</span>,</span>
                      <span><span>Not On The Running Order</span></span></h4>
                </a>
                </body></html>
                """.trimIndent()
            val runningOrderImporter = BerghainWebsiteImporter(htmlFetcher, Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC))
            val detailUrl = "https://www.berghain.berlin/de/event/80744/"
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse(overview, sourceUrl), etag = null, lastModified = null)
            coEvery { htmlFetcher.fetchDocument(detailUrl) } returns
                Jsoup.parse(loadFixture("scraper/berghain/berghain-detail-running-order.html"), detailUrl)

            val result = runningOrderImporter.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            val artists =
                result.events
                    .single()
                    .artists
                    .associateBy { it.name }

            // The lineup is the programme's: its five acts, its roles, none of the running order's other names.
            artists.keys shouldBe setOf("Joline Scheffler", "Colin Benders", "nd_baumecker", "Jorkes", "Not On The Running Order")
            artists.getValue("Colin Benders").role shouldBe "HEADLINER"
            artists.getValue("Joline Scheffler").setStart shouldBe Instant.parse("2026-09-26T21:59:00Z")
            artists.getValue("Colin Benders").setEnd shouldBe Instant.parse("2026-09-27T06:30:00Z")
            artists.getValue("Jorkes").setStart shouldBe Instant.parse("2026-09-27T23:00:00Z")
            artists.getValue("nd_baumecker").stage shouldBe "Panorama Bar"
            artists.getValue("Not On The Running Order").setStart.shouldBeNull()
        }

    @Test
    fun `eventSource matches the expected enum value`() {
        importer.eventSource shouldBe EventSource.BERGHAIN
    }

    @Test
    fun `folds a Halle exhibition's days into one run and bills no act`() =
        runTest {
            val days = listOf("83113" to "02.10.2026", "83114" to "03.10.2026", "83115" to "04.10.2026")
            val overview =
                days.joinToString("") { (id, date) ->
                    """<a href="/de/event/$id/" class="upcoming-event"><p>Freitag <span class="font-bold">$date</span> beginn 17:00</p>""" +
                        """<h2>A SHROUD WOVEN OF SOLAR THREADS</h2><h3>Halle</h3>""" +
                        """<h4><span class="font-bold"><span>A Shroud Woven of Solar Threads</span></span></h4></a>"""
                }
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body>$overview</body></html>", sourceUrl), etag = null, lastModified = null)
            val detail = loadFixture("scraper/berghain/berghain-detail-exhibition.html")
            days.forEach { (id, date) ->
                val url = "https://www.berghain.berlin/de/event/$id/"
                coEvery { htmlFetcher.fetchDocument(url) } returns Jsoup.parse(detail.replace("02.10.2026", date), url)
            }

            val result = importer.importEvents(sourceUrl, null, null)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val run = result.events.single()
            run.eventType shouldBe "EXHIBITION"
            run.sourceId shouldBe "berghain:exhibition-a-shroud-woven-of-solar-threads"
            run.eventDate shouldBe LocalDate.of(2026, 10, 2)
            run.endDate shouldBe LocalDate.of(2026, 10, 4)
            run.artists shouldBe emptyList()
        }

    @Test
    fun `a day whose page failed folds into the run another day confirms`() =
        runTest {
            val days = listOf("83113" to "02.10.2026", "83114" to "03.10.2026", "83115" to "04.10.2026")
            val overview =
                days.joinToString("") { (id, date) ->
                    """<a href="/de/event/$id/" class="upcoming-event"><p>Freitag <span class="font-bold">$date</span> beginn 17:00</p>""" +
                        """<h2>A SHROUD WOVEN OF SOLAR THREADS</h2><h3>Halle</h3>""" +
                        """<h4><span class="font-bold"><span>A Shroud Woven of Solar Threads</span></span></h4></a>"""
                }
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body>$overview</body></html>", sourceUrl), etag = null, lastModified = null)
            val detail = loadFixture("scraper/berghain/berghain-detail-exhibition.html")
            // The first day's page fails; the default stub answers with an empty document (#2542).
            days.drop(1).forEach { (id, date) ->
                val url = "https://www.berghain.berlin/de/event/$id/"
                coEvery { htmlFetcher.fetchDocument(url) } returns Jsoup.parse(detail.replace("02.10.2026", date), url)
            }

            val result = importer.importEvents(sourceUrl, null, null)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val run = result.events.single()
            run.sourceId shouldBe "berghain:exhibition-a-shroud-woven-of-solar-threads"
            run.eventDate shouldBe LocalDate.of(2026, 10, 2)
            run.endDate shouldBe LocalDate.of(2026, 10, 4)
            run.artists shouldBe emptyList()
            run.detailUnavailable shouldBe false
        }
}
