package de.norm.events.scraper.klunkerkranich

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [KlunkerkranichWebsiteImporter].
 *
 * The importer fetches the programme once and then one event page per night, so these cover the
 * fetch count as well as what the event page contributes on top of the listing — the blurb, the
 * entry charge and the full-size poster — and what is kept when that page cannot be fetched.
 */
class KlunkerkranichWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()

    /** The snapshot's capture date, so the listing scraper's year inference is stable. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val importer = KlunkerkranichWebsiteImporter(htmlFetcher, clock)
    private val listingUrl = "https://klunkerkranich.org/events/"

    private fun stubListing() {
        coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
            FetchResult.Success(
                document = fixtureDocument("klunkerkranich/klunkerkranich-overview.html", listingUrl),
                etag = "\"klunkerkranich-etag\"",
                lastModified = "Tue, 04 Aug 2026 08:53:43 GMT"
            )
    }

    private fun stubEventPages(name: String = "klunkerkranich-detail-price-range.html") {
        coEvery { htmlFetcher.fetchDocument(any()) } answers { fixtureDocument("klunkerkranich/$name", firstArg<String>()) }
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.KLUNKERKRANICH
    }

    @Test
    fun `returns NotModified when the programme is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(listingUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports the published programme and propagates conditional headers`() =
        runTest {
            stubListing()
            stubEventPages()

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 12
            result.events.first().eventDate shouldBe LocalDate.of(2026, 8, 5)
            result.etag shouldBe "\"klunkerkranich-etag\""
            result.lastModified shouldBe "Tue, 04 Aug 2026 08:53:43 GMT"
        }

    @Test
    fun `adds the blurb, the entry charge and the full-size poster from each event page`() =
        runTest {
            stubListing()
            stubEventPages()

            val result = importer.importEvents(listingUrl) as ImportResult.Success
            // Every event page is stubbed with the same fixture, so assert on the night it belongs to.
            val night = result.events.first { it.eventDate == LocalDate.of(2026, 8, 8) }

            night.description shouldStartWith "Ein zweites Mal in 2026"
            night.priceNote shouldBe "5-9€"
            night.priceBoxOffice.shouldBeNull()
            // The listing's thumbnail is a 520x320 crop; the event page links the original.
            night.imageUrl shouldBe "https://klunkerkranich.org/wp-content/uploads/2025/10/Klunkerkranich-kunst-10.Oct25.jpg"
            night.detailUnavailable shouldBe false
        }

    /**
     * The 2026-11-18 programme, whose Django Lassi, HIVE TEA and VINYL POP UP titles carry no line-up marker. Their own
     * pages are served for them, the HMWL page for every other night.
     */
    private fun stubNoMarkerProgramme() {
        val url = "https://klunkerkranich.org/events/"
        coEvery { htmlFetcher.fetch(url, any(), any()) } returns
            FetchResult.Success(fixtureDocument("klunkerkranich/klunkerkranich-overview-no-marker.html", url), etag = null, lastModified = null)
        coEvery { htmlFetcher.fetchDocument(any()) } answers {
            val page = firstArg<String>()
            val name =
                when {
                    "django-lassi" in page -> "klunkerkranich-detail-django-lassi.html"
                    "hive-tea" in page -> "klunkerkranich-detail-hive-tea.html"
                    "vinyl-pop-up" in page -> "klunkerkranich-detail-vinyl-pop-up.html"
                    else -> "klunkerkranich-detail-price-range.html"
                }
            fixtureDocument("klunkerkranich/$name", page)
        }
    }

    private suspend fun importNoMarkerNight(slugPart: String): ScrapedEvent {
        stubNoMarkerProgramme()
        val result = importer.importEvents(listingUrl) as ImportResult.Success
        return result.events.single { slugPart in it.sourceId }
    }

    private fun ScrapedEvent.lineup() = artists.map { it.name to it.role }

    @Test
    fun `bills the event page's line-up when the title has no line-up marker`() =
        runTest {
            val night = importNoMarkerNight("django-lassi")

            night.title shouldBe "Django Lassi – HEAP Album Release | DJ Seraphim Balkanique · Gypsy · Swingcore"
            // The block shouts `DJANGO LASSI *live`; the title's spelling wins. `HEAP Album Release Show` restates the title.
            night.lineup() shouldBe listOf("Django Lassi" to ArtistRole.LIVE, "Seraphim" to ArtistRole.DJ)
        }

    @Test
    fun `drops the show line, the host, the link and the affiliation from the page's line-up`() =
        runTest {
            val night = importNoMarkerNight("hive-tea")

            // `HIVE TEA DRAG SHOW *live` is the night itself, `hosted by Gieza Poke` its host.
            night.lineup() shouldBe listOf("Emorine" to ArtistRole.DJ, "Vamilienfa†er" to ArtistRole.DJ)
        }

    @Test
    fun `bills no act from a page whose block only restates the night and credits its presenter`() =
        runTest {
            importNoMarkerNight("vinyl-pop-up").artists.shouldBeEmpty()
        }

    @Test
    fun `keeps the title's line-up over the page's block when the title has a marker`() =
        runTest {
            val night = importNoMarkerNight("les-moches")

            // The HMWL page served for it bills Lex Ludlow; the title's acts stand.
            night.artists.map { it.name } shouldBe listOf("Zuendhoelzl", "Silvi Knallt", "Dämser", "Tigo")
        }

    @Test
    fun `stores a lone entry figure as a box-office price`() =
        runTest {
            stubListing()
            stubEventPages("klunkerkranich-detail-single-price.html")

            val result = importer.importEvents(listingUrl) as ImportResult.Success

            result.events.first().priceBoxOffice shouldBe BigDecimal("3")
            result.events
                .first()
                .priceNote
                .shouldBeNull()
        }

    @Test
    fun `fetches one event page per night`() =
        runTest {
            stubListing()
            stubEventPages()

            importer.importEvents(listingUrl)

            coVerify(exactly = 12) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `keeps the listing data when an event page cannot be fetched`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("boom")

            val result = importer.importEvents(listingUrl) as ImportResult.Success
            val night = result.events.first()

            result.events shouldHaveSize 12
            night.title shouldBe "WOCHENMITTE w. Pascale Project"
            night.description.shouldBeNull()
            night.priceNote.shouldBeNull()
            night.imageUrl shouldBe
                "https://klunkerkranich.org/wp-content/uploads/2026/07/Klunkerkranich-deko-su-hda-18.Jun26-520x320.jpg"
        }

    @Test
    fun `marks the listing row when the event page fetch fails`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("connection reset")

            val result = importer.importEvents(listingUrl) as ImportResult.Success

            result.events.map { it.detailUnavailable }.distinct() shouldBe listOf(true)
            // The card's thumbnail stands in for the poster, so a stored poster wins over it (#2465).
            result.events.map { it.detailPageOwns }.distinct() shouldBe listOf(setOf(ScrapedField.IMAGE))
        }

    @Test
    fun `marks the listing row when the event page yields no blurb`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse("<html><body></body></html>", firstArg<String>()) }

            val result = importer.importEvents(listingUrl) as ImportResult.Success
            val night = result.events.first()

            night.description.shouldBeNull()
            night.detailUnavailable shouldBe true
            night.detailPageOwns shouldBe emptySet()
        }

    @Test
    fun `returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body><main class=\"c-events-overview\"></main></body></html>", listingUrl),
                    etag = null,
                    lastModified = null
                )

            val result = importer.importEvents(listingUrl) as ImportResult.Success

            result.events.shouldBeEmpty()
        }
}
