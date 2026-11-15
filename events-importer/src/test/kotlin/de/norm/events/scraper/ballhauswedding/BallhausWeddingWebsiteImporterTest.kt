package de.norm.events.scraper.ballhauswedding

import de.norm.events.event.EventStatus
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class BallhausWeddingWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val url = "https://www.ballhauswedding.de/veranstaltungen"
    private val clock = Clock.fixed(LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
    private val importer = BallhausWeddingWebsiteImporter(htmlFetcher, clock)

    @Test
    fun `reads the programme, then only the entries' own Wix Events pages`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(match { it.contains("/details-registrierung/") }) } throws IllegalStateException("no fixture")
            stubFetcher(
                url to "ballhauswedding/ballhauswedding-veranstaltungen.html",
                STREISAND to "ballhauswedding/ballhauswedding-event-barbra-streisand.html",
                fetcher = htmlFetcher
            )

            val result = importer.importEvents(url, null, null).shouldBeInstanceOf<ImportResult.Success>()

            importer.eventSource shouldBe EventSource.BALLHAUS_WEDDING
            val streisand = result.events.first()
            streisand.imageUrl?.startsWith("https://static.wixstatic.com/") shouldBe true
            streisand.detailUnavailable shouldBe false
            result.events.first { it.title == "Berlin - Odessa - Express" }.detailUnavailable shouldBe true
            result.events.first { it.title.startsWith("Tango") }.detailUnavailable shouldBe false
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(url) }
        }

    private val november = Clock.fixed(LocalDate.of(2026, 11, 12).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
    private val novemberImporter = BallhausWeddingWebsiteImporter(htmlFetcher, november)

    private fun stubNovember() {
        coEvery { htmlFetcher.fetchDocument(match { it.contains("/details-registrierung/") }) } throws IllegalStateException("no fixture")
        stubFetcher(
            url to "ballhauswedding/ballhauswedding-veranstaltungen-2026-11-13.html",
            SITEMAP to "ballhauswedding/ballhauswedding-event-pages-sitemap.xml",
            DISCOFOX to "ballhauswedding/ballhauswedding-event-discofox-nacht-2026-12-18.html",
            SHOW to "ballhauswedding/ballhauswedding-event-improvisationstheater-2026-12-16-cancelled.html",
            SHOW_COPY to "ballhauswedding/ballhauswedding-event-improvisationstheater-2026-12-16-1.html",
            fetcher = htmlFetcher
        )
    }

    private fun List<ScrapedEvent>.on(
        date: LocalDate,
        title: String
    ) = first { it.eventDate == date && it.title.startsWith(title) }

    @Test
    fun `enriches an unlinked night from its dated sitemap page as a linked page`() =
        runTest {
            stubNovember()

            val events = novemberImporter.importEvents(url, null, null).shouldBeInstanceOf<ImportResult.Success>().events

            val discofox = events.on(LocalDate.of(2026, 12, 18), "Discofox")
            discofox.sourceUrl shouldBe DISCOFOX
            discofox.ticketUrl shouldBe DISCOFOX
            discofox.description.shouldNotBeNull() shouldStartWith "die Discofox-Nacht im Ballhaus Wedding"
            discofox.endDate shouldBe LocalDate.of(2026, 12, 19)
            discofox.endTime shouldBe LocalTime.of(0, 30)
            discofox.imageUrl.shouldNotBeNull() shouldStartWith "https://static.wixstatic.com/media/"
            discofox.detailUnavailable shouldBe false

            val show = events.on(LocalDate.of(2026, 12, 16), "Theatersport")
            show.sourceUrl shouldBe SHOW_COPY
            show.ticketUrl shouldBe SHOW_COPY
            show.status shouldBe EventStatus.SCHEDULED.name
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(SHOW) }
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(SITEMAP) }

            val unreadable = events.on(LocalDate.of(2027, 1, 29), "Discofox")
            unreadable.sourceUrl shouldBe url
            unreadable.detailUnavailable shouldBe true
            events.on(LocalDate.of(2026, 12, 15), "Bachata").let {
                it.sourceUrl shouldBe url
                it.ticketUrl.shouldBeNull()
                it.detailUnavailable shouldBe false
            }
        }

    @Test
    fun `skips a cancelled copy for the next one and leaves the programme's status to the programme`() =
        runTest {
            stubNovember()
            stubFetcher(
                SHOW_COPY to "ballhauswedding/ballhauswedding-event-improvisationstheater-2026-12-16-cancelled.html",
                SHOW to "ballhauswedding/ballhauswedding-event-improvisationstheater-2026-12-16-1.html",
                fetcher = htmlFetcher
            )

            val show =
                novemberImporter
                    .importEvents(
                        url,
                        null,
                        null
                    ).shouldBeInstanceOf<ImportResult.Success>()
                    .events
                    .on(LocalDate.of(2026, 12, 16), "Theatersport")

            show.sourceUrl shouldBe SHOW
            show.status shouldBe EventStatus.SCHEDULED.name
        }

    @Test
    fun `keeps an entry's own ticket link when a sitemap page matches it`() =
        runTest {
            stubNovember()
            val gruselPage = "https://www.ballhauswedding.de/details-registrierung/jack-the-ripper-original-gruseldinner-2027-01-17-18-00"
            coEvery { htmlFetcher.fetchDocument(SITEMAP) } returns Jsoup.parse("<urlset><url><loc>$gruselPage</loc></url></urlset>", SITEMAP)
            stubFetcher(gruselPage to "ballhauswedding/ballhauswedding-event-discofox-nacht-2026-12-18.html", fetcher = htmlFetcher)

            val dinner =
                novemberImporter
                    .importEvents(url, null, null)
                    .shouldBeInstanceOf<ImportResult.Success>()
                    .events
                    .on(LocalDate.of(2027, 1, 17), "Jack")

            dinner.sourceUrl shouldBe gruselPage
            dinner.ticketUrl shouldBe "https://www.gruseldinner.de/show/r-17-01-27/"
        }

    @Test
    fun `imports the programme as before when the sitemap fails`() =
        runTest {
            stubNovember()
            coEvery { htmlFetcher.fetchDocument(SITEMAP) } throws IllegalStateException("sitemap down")

            val events = novemberImporter.importEvents(url, null, null).shouldBeInstanceOf<ImportResult.Success>().events

            events.on(LocalDate.of(2026, 12, 18), "Discofox").let {
                it.sourceUrl shouldBe url
                it.ticketUrl.shouldBeNull()
                it.detailUnavailable shouldBe false
            }
        }

    private companion object {
        const val STREISAND = "https://www.ballhauswedding.de/details-registrierung/legenden-barbara-streisand"
        const val SITEMAP = "https://www.ballhauswedding.de/event-pages-sitemap.xml"
        const val DISCOFOX = "https://www.ballhauswedding.de/details-registrierung/discofox-nacht-2026-12-18-20-00"
        const val SHOW = "https://www.ballhauswedding.de/details-registrierung/die-ballhaus-show-improvisationstheater-2026-12-16-19-30"
        const val SHOW_COPY = "$SHOW-1"
    }
}
