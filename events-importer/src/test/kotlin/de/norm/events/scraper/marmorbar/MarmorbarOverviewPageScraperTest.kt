package de.norm.events.scraper.marmorbar

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [MarmorbarOverviewPageScraper], against the English event list captured on 2026-10-16. */
class MarmorbarOverviewPageScraperTest {
    private val scraper = MarmorbarOverviewPageScraper()
    private val baseUrl = "https://www.marmorbar.com/en/event-list"

    private fun fixture() =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/marmorbar/marmorbar-overview.html")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    private val events by lazy { scraper.scrape(fixture(), baseUrl).associateBy { it.title } }

    @Test
    fun `reads every event in the payload`() {
        events.keys shouldBe
            setOf("hauswerks - afterglow", "Paradise Traxx Label Launch Party", "FLUR", "KYLIE WEARS BERGHAUS", "707 TAKEOVER 003")
    }

    @Test
    fun `maps a Wix-ticketed night in Berlin time, keyed on the Wix id`() {
        val kylie = events.getValue("KYLIE WEARS BERGHAUS")

        kylie.eventDate shouldBe LocalDate.of(2026, 11, 6)
        kylie.startTime shouldBe LocalTime.of(22, 0)
        kylie.endDate shouldBe LocalDate.of(2026, 11, 7)
        kylie.endTime shouldBe LocalTime.of(5, 0)
        kylie.sourceId shouldBe "${EventSource.MARMORBAR.sourceIdPrefix}5be04042-923a-4891-bacd-fa876ff347b7"
        kylie.sourceUrl shouldBe "https://www.marmorbar.com/en/event-details/kylie-wears-berghaus"
        kylie.ticketUrl shouldBe kylie.sourceUrl
        kylie.imageUrl shouldBe "https://static.wixstatic.com/media/94dd0f_cceb12f7130c47cbaed4ee6db39b56da~mv2.png"
        kylie.description shouldBe
            "After multiple sold out shows around the UK, act and party Kylie Wears Berghaus makes its European debut in Berlin this November at Marmorbar."
        kylie.status shouldBe EventStatus.SCHEDULED.name
        kylie.soldOut shouldBe false
        kylie.artists.shouldBeEmpty()
    }

    @Test
    fun `leaves the listing's fee-inclusive price to the event page`() {
        events.values.map { it.pricePresale }.toSet() shouldBe setOf(null)
    }

    @Test
    fun `a night sold only at the door links no ticket shop`() {
        events.getValue("FLUR").ticketUrl.shouldBeNull()
    }

    @Test
    fun `types an unmarked night as a DJ night and a party cue as a party`() {
        val hauswerks = events.getValue("hauswerks - afterglow")
        hauswerks.eventType shouldBe EventType.PARTY.name
        hauswerks.typeIsFallback shouldBe true
        val launch = events.getValue("Paradise Traxx Label Launch Party")
        launch.eventType shouldBe EventType.PARTY.name
        launch.typeIsFallback shouldBe false
    }

    @Test
    fun `a page without a warmup payload yields no events`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl) shouldHaveSize 0
    }
}
