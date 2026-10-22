package de.norm.events.scraper.aeg

import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
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

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/aeg/$name")!!
            .bufferedReader()
            .readText()

    private suspend fun importedEvent(
        detailPage: String?,
        overviewPage: String = "ubereatsmusichall-overview-cobill.html",
        detailUrl: String = this.detailUrl
    ): de.norm.events.scraper.ScrapedEvent {
        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(readFixture(overviewPage), overviewUrl),
                etag = null,
                lastModified = null
            )
        // The base class follows each row with `fetchDocument`; a throw is its degrade-to-overview path.
        if (detailPage == null) {
            coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no detail page")
        } else {
            coEvery { htmlFetcher.fetchDocument(detailUrl) } returns Jsoup.parse(readFixture(detailPage), detailUrl)
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
            event.artists.map { it.name to it.role } shouldBe listOf("Till Reiners" to "HEADLINER")
        }

    @Test
    fun `bills no act off a bare-name comedy title without a detail page`() =
        runTest {
            importedEvent(null, overviewPage = "ubereatsmusichall-overview-solo-comedy.html").artists shouldBe emptyList()
        }
}
