package de.norm.events.scraper.huxleys

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
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
 * Unit tests for [HuxleysWebsiteImporter].
 *
 * Uses static HTML fixtures and a mocked [HtmlFetcher]. The focus is the merge, which is unusual
 * here in two ways: the detail page has no heading, so the **overview's** title wins; and a
 * relocation is announced only in the listing's change note, so the overview's status wins too.
 */
class HuxleysWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: HuxleysWebsiteImporter

    private val overviewUrl = "https://huxleysneuewelt.de/events"
    private val thieveryUrl = "https://huxleysneuewelt.de/event/2026-08-02-thievery-corporation"
    private val kardUrl = "https://huxleysneuewelt.de/event/2026-09-01-kard"
    private val currentJoysUrl = "https://huxleysneuewelt.de/event/2026-08-18-current-joys"
    private val rockLegendsUrl = "https://huxleysneuewelt.de/event/2026-11-06-rock-legends"
    private val corruptedBloodUrl = "https://huxleysneuewelt.de/event/2026-09-04-corrupted-blood-club-show"

    /**
     * The two elements that make a label showcase recognisable, written out rather than captured:
     * the venue's real page carries them among 3000 lines of theme markup, and the point of the
     * test is the `.tourtitel` credit beside a title that repeats the label's name.
     */
    private val labelShowcaseDetailPage =
        """
        <html><head><meta property="og:title" content="Corrupted Blood Club Show - Huxleys Neue Welt"></head>
        <body><article class="event">
          <div class="tourtitel"><span>Corrupted Blood Records presents</span></div>
        </article></body></html>
        """.trimIndent()

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/huxleys/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = HuxleysWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("huxleys-overview.html"), overviewUrl),
                etag = "\"huxleys-etag\"",
                lastModified = "Sat, 01 Aug 2026 03:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(thieveryUrl) } returns Jsoup.parse(fixture("huxleys-detail-soldout.html"), thieveryUrl)
        coEvery { htmlFetcher.fetchDocument(kardUrl) } returns Jsoup.parse(fixture("huxleys-detail-simple.html"), kardUrl)
        coEvery { htmlFetcher.fetchDocument(currentJoysUrl) } returns Jsoup.parse(fixture("huxleys-detail-relocated.html"), currentJoysUrl)
        coEvery { htmlFetcher.fetchDocument(rockLegendsUrl) } returns Jsoup.parse(fixture("huxleys-detail-cancelled.html"), rockLegendsUrl)

        val stubbed = setOf(thieveryUrl, kardUrl, currentJoysUrl, rockLegendsUrl)
        coEvery { htmlFetcher.fetchDocument(match { it !in stubbed }) } returns Jsoup.parse("<html><body></body></html>", overviewUrl)
    }

    private fun event(
        result: ImportResult,
        slug: String
    ): ScrapedEvent {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events.first { it.sourceId == "huxleys:$slug" }
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.HUXLEYS
    }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(overviewUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports all events and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 107
            result.etag shouldBe "\"huxleys-etag\""
            result.lastModified shouldBe "Sat, 01 Aug 2026 03:00:00 GMT"
        }

    @Test
    fun `merges the detail page's tour name, image, genre and promoter onto the listing`() =
        runTest {
            val thievery = event(importer.importEvents(overviewUrl), "2026-08-02-thievery-corporation")
            // Only the detail page carries these:
            thievery.description.shouldNotBeNull()
            thievery.imageUrl.shouldNotBeNull()
            thievery.ticketUrl.shouldNotBeNull()
            thievery.genre shouldBe "Electronic, Fusion, Indietronica"
            thievery.promoters shouldContainExactly listOf("Trinity Music")
            // The subtitle combines the detail page's tour name with the listing's support line.
            thievery.subtitle shouldBe "30TH ANNIVERSARY TOUR | + Support: PECES RAROS"
            thievery.soldOut shouldBe true
            thievery.eventDate shouldBe LocalDate.of(2026, 8, 2)
            thievery.startTime shouldBe LocalTime.of(20, 0)
        }

    @Test
    fun `keeps the listing's title, since the detail page has no heading`() =
        runTest {
            // og:title would give the same act here, but the listing is the venue's own display text.
            event(importer.importEvents(overviewUrl), "2026-09-01-kard").title shouldBe "KARD"
        }

    @Test
    fun `keeps the listing's support acts through the merge`() =
        runTest {
            event(importer.importEvents(overviewUrl), "2026-08-02-thievery-corporation").artists shouldContainExactly
                listOf(
                    ScrapedArtist("Thievery Corporation", "HEADLINER"),
                    ScrapedArtist("PECES RAROS", "SUPPORT")
                )
        }

    @Test
    fun `stores no artist for a label showcase, whose credit only the detail page carries`() =
        runTest {
            // The listing shows a bare concert title and would mint the night's own name as a
            // performer; only `.tourtitel` says the label is presenting. Neither page can reach that
            // conclusion alone, which is why the merge rebuilds the lineup instead of picking one.
            coEvery { htmlFetcher.fetchDocument(corruptedBloodUrl) } returns
                Jsoup.parse(labelShowcaseDetailPage, corruptedBloodUrl)

            val showcase = event(importer.importEvents(overviewUrl), "2026-09-04-corrupted-blood-club-show")
            showcase.title shouldBe "Corrupted Blood Club Show"
            showcase.subtitle shouldBe "Corrupted Blood Records presents"
            showcase.eventType shouldBe "CONCERT"
            showcase.artists.shouldBeEmpty()
        }

    @Test
    fun `keeps the relocation the detail page does not know about`() =
        runTest {
            // The note lives only on the listing; the detail page would report SCHEDULED.
            val currentJoys = event(importer.importEvents(overviewUrl), "2026-08-18-current-joys")
            currentJoys.status shouldBe "RELOCATED"
            // …while still taking that page's price and promoter.
            currentJoys.priceNote shouldBe "VVK: 28 € (zzgl. Gebühr)"
            currentJoys.promoters shouldContainExactly listOf("Puschen")
        }

    @Test
    fun `keeps the cancelled status through the merge`() =
        runTest {
            event(importer.importEvents(overviewUrl), "2026-11-06-rock-legends").status shouldBe "CANCELLED"
        }

    @Test
    fun `degrades to listing data when a detail page is unavailable`() =
        runTest {
            val other = event(importer.importEvents(overviewUrl), "2026-08-07-electric-bassboy")
            other.title shouldBe "Electric Bassboy"
            other.eventDate shouldBe LocalDate.of(2026, 8, 7)
            other.soldOut shouldBe true
            other.genre.shouldBeNull()
        }
}
