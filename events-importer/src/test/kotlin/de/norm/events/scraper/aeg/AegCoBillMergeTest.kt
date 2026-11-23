package de.norm.events.scraper.aeg

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/**
 * The one place a comma in an AEG title can be decided (#1832), and a bare comedy title read as an act.
 *
 * The listing owns the title and mints the roster from it; the description lives on the detail
 * page. So a bill the listing stores as one artist is only re-read at the merge, and this drives
 * the whole importer to prove the two halves meet there.
 */
class AegCoBillMergeTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val overviewUrl = "https://www.uber-eats-music-hall.de/events/all"
    private val detailUrl = "https://www.uber-eats-music-hall.de/events/detail/d-block-french-montana/2026-11-05-2000"

    private suspend fun importedEvent(
        detailPage: String?,
        overviewPage: String = "ubereatsmusichall-overview-cobill.html",
        detailUrl: String = this.detailUrl
    ): de.norm.events.scraper.ScrapedEvent {
        stubFetcher(overviewUrl to "aeg/$overviewPage", fetcher = htmlFetcher)
        // The base class follows each row with `fetchDocument`; a throw is its degrade-to-overview path.
        if (detailPage == null) {
            coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no detail page")
        } else {
            stubFetcher(detailUrl to "aeg/$detailPage", fetcher = htmlFetcher)
        }
        val result = UberEatsMusicHallWebsiteImporter(htmlFetcher).importEvents(overviewUrl, null, null)
        result.shouldBeInstanceOf<ImportResult.Success>()
        result.events shouldHaveSize 1
        return result.events.single()
    }

    @Test
    fun `bills both acts of a comma title the detail page's description names`() =
        runTest {
            val event = importedEvent("ubereatsmusichall-detail-cobill.html")
            event.title shouldBe "D-Block Europe, French Montana"
            event.artists.map { it.name } shouldBe listOf("D-Block Europe", "French Montana")
        }

    @Test
    fun `bills a Support Act paragraph after the headliners`() =
        runTest {
            // Event 14579 lost Blancmange, the support of The Human League.
            importedEvent("ubereatsmusichall-detail-cobill-support.html").artists.map { it.name to it.role } shouldBe
                listOf("D-Block Europe" to ArtistRole.HEADLINER, "French Montana" to ArtistRole.HEADLINER, "Blancmange" to ArtistRole.SUPPORT)
        }

    @Test
    fun `keeps the listing's own roster when no detail page answers`() =
        runTest {
            // The fallback path: without the description the comma decides nothing, exactly as before.
            importedEvent(null).artists.map { it.name } shouldBe listOf("D-Block Europe, French Montana")
        }

    @Test
    fun `bills a bare-name comedy title the description introduces as a comedian`() =
        runTest {
            val event =
                importedEvent(
                    "ubereatsmusichall-detail-solo-comedy.html",
                    overviewPage = "ubereatsmusichall-overview-solo-comedy.html",
                    detailUrl = "https://www.uber-eats-music-hall.de/events/detail/till-reiners/2027-11-06-2000"
                )
            event.eventType shouldBe "COMEDY"
            event.artists.map { it.name to it.role } shouldBe listOf("Till Reiners" to ArtistRole.HEADLINER)
        }

    @Test
    fun `bills no act off a bare-name comedy title without a detail page`() =
        runTest {
            importedEvent(null, overviewPage = "ubereatsmusichall-overview-solo-comedy.html").artists shouldBe emptyList()
        }

    @Test
    fun `bills no headliner for a tribute show the description quotes under the title, as event 15199`() =
        runTest {
            val detail = "https://www.uber-eats-music-hall.de/events/detail/roger-waters-presents-legacy/2027-11-11-2000"
            val overview =
                """
                <div class="event-list"><div class="active-date entry clearfix" data-category="3" data-date="2027-11">
                  <div class="details"><div class="date"><span class="m-date__singleDate"><span class="m-date__weekday">Donnerstag, </span>
                    <span class="m-date__day">11. </span><span class="m-date__month">Nov. </span><span class="m-date__year">2027, </span>
                    <span class="m-date__hour"> 20:00 Uhr</span></span></div>
                  <h3 class="event-title"><a href="$detail" title="Mehr Infos">ROGER WATERS PRESENTS LEGACY</a></h3></div>
                </div></div>
                """.trimIndent()
            val detailPage =
                """
                <h1 class="summary">ROGER WATERS PRESENTS LEGACY in der Uber Eats Music Hall</h1>
                <div class="event_body">
                  <p>Am 11. November 2027 kommt "ROGER WATERS PRESENTS LEGACY - A Pink Floyd Tribute Show" in die Uber Eats Music Hall nach Berlin.</p>
                  <p>Gründungsmitglied Roger Waters übernimmt persönlich die Regie.</p>
                  <p><strong>Support Act: Blancmange</strong></p>
                </div>
                """.trimIndent()
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any(), any()) } returns
                FetchResult.Success(Jsoup.parse(overview, overviewUrl), etag = null, lastModified = null)
            coEvery { htmlFetcher.fetchDocument(detail) } returns Jsoup.parse(detailPage, detail)

            val result = UberEatsMusicHallWebsiteImporter(htmlFetcher).importEvents(overviewUrl, null, null)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.single()
            event.eventType shouldBe "CONCERT"
            event.artists.map { it.name to it.role } shouldBe listOf("Blancmange" to ArtistRole.SUPPORT)
        }
}
