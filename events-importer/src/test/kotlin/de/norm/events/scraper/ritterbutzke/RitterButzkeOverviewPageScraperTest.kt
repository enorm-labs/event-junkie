package de.norm.events.scraper.ritterbutzke

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [RitterButzkeOverviewPageScraper].
 *
 * Parses a static snapshot of Ritter Butzke's `/events` page for deterministic, offline-safe
 * testing without HTTP fetching.
 */
class RitterButzkeOverviewPageScraperTest {
    private val scraper = RitterButzkeOverviewPageScraper()
    private val baseUrl = "https://club.ritterbutzke.com/events"

    private val events: List<ScrapedEvent> by lazy { scraper.scrape(fixtureDocument("ritterbutzke/ritterbutzke-overview.html", baseUrl), baseUrl) }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `discovers every card on the listing`() {
        events shouldHaveSize 29
    }

    @Test
    fun `maps a fully populated card`() {
        val unison = event("ritter_butzke:070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia")
        unison.title shouldBe "Unison w/ Zapped Records, Nizar Sarakbi, Josefina Tapia"
        unison.eventType shouldBe EventType.PARTY.name
        unison.eventDate shouldBe LocalDate.of(2026, 8, 7)
        unison.sourceUrl shouldBe
            "https://club.ritterbutzke.com/event/070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia"
        unison.imageUrl!! shouldStartWith "https://club.ritterbutzke.com/img/"
        // A party's title is an event name, not an act.
        unison.artists.shouldBeEmpty()
    }

    @Test
    fun `reads the rendered date rather than the stale date in the slug`() {
        // The slug says 31.07.26, but the show moved: the card renders 04.09.26. Reading the slug
        // would file it five weeks early, and re-minting the sourceId would orphan the stored row.
        val deeportament = event("ritter_butzke:310726-DeeportamentCommunityw-NicoMorano-OpenAir-Indoor")
        deeportament.eventDate shouldBe LocalDate.of(2026, 9, 4)
    }

    @Test
    fun `parses the venue's two-digit-year date`() {
        // The card writes "10.04.27"; the detail page restates it as "10.04.2027".
        event("ritter_butzke:100427-WeltauswahlbyExtrawelt").eventDate shouldBe LocalDate.of(2027, 4, 10)
    }

    @Test
    fun `keeps several events sharing one date apart by their slugs`() {
        // The club runs multiple floors, so a date routinely carries two or three nights.
        val eighthOfAugust = events.filter { it.eventDate == LocalDate.of(2026, 8, 8) }
        eighthOfAugust shouldHaveSize 3
        eighthOfAugust.map { it.sourceId }.toSet() shouldHaveSize 3
    }

    @Test
    fun `leaves the detail-only fields empty`() {
        val unison = event("ritter_butzke:070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia")
        unison.startTime.shouldBeNull()
        unison.doorsTime.shouldBeNull()
        unison.ticketUrl.shouldBeNull()
    }

    @Test
    fun `publishes no prices anywhere on the listing`() {
        events.forEach {
            it.pricePresale.shouldBeNull()
            it.priceBoxOffice.shouldBeNull()
            it.soldOut shouldBe false
        }
    }

    @Test
    fun `parses every card into a resolved date`() {
        events.none { it.eventDate == UNRESOLVED_EVENT_DATE } shouldBe true
    }

    @Test
    fun `returns an empty list for a page without cards`() {
        val document = Jsoup.parse("<html><body><div class='events-container'></div></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}
