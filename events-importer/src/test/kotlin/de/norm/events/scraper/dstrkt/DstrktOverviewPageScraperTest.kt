package de.norm.events.scraper.dstrkt

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class DstrktOverviewPageScraperTest {
    private val scraper = DstrktOverviewPageScraper()
    private val baseUrl = "https://www.dstrkt.de/"

    private fun scrape(fixture: String) =
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/dstrkt/$fixture")!!
                    .bufferedReader()
                    .readText(),
                baseUrl
            ),
            baseUrl
        )

    @Test
    fun `reads every record of the collection`() {
        scrape("dstrkt-overview.html").map { it.title } shouldContainExactly
            listOf("SEQUENCED MINDS", "ELYSIUM", "ONYX NYE: 'NEW ERA'")
    }

    @Test
    fun `converts the UTC date to the Berlin wall clock the page prints`() {
        val events = scrape("dstrkt-overview.html").associateBy { it.title }

        // 2026-10-09T20:00Z in summer time, 2026-11-07T22:00Z in winter time: the page prints 22:00 and 23:00.
        events.getValue("SEQUENCED MINDS").eventDate shouldBe LocalDate.of(2026, 10, 9)
        events.getValue("SEQUENCED MINDS").startTime shouldBe LocalTime.of(22, 0)
        events.getValue("ELYSIUM").eventDate shouldBe LocalDate.of(2026, 11, 7)
        events.getValue("ELYSIUM").startTime shouldBe LocalTime.of(23, 0)
    }

    @Test
    fun `maps one record fully`() {
        val nye = scrape("dstrkt-overview.html").single { it.title.startsWith("ONYX") }

        nye.eventDate shouldBe LocalDate.of(2026, 12, 30)
        nye.startTime shouldBe LocalTime.of(22, 0)
        nye.endDate shouldBe LocalDate.of(2027, 1, 1)
        nye.endTime.shouldBeNull()
        nye.ticketUrl shouldBe "https://de.ra.co/events/2510425"
        nye.sourceUrl shouldBe baseUrl
        nye.sourceId shouldBe "dstrkt:cc6d05c7-4954-4386-a894-df1f07ccada9"
        nye.eventType shouldBe EventType.PARTY.name
        nye.typeIsFallback shouldBe true
        nye.description.shouldBeNull()
        nye.artists.shouldBeEmpty()
    }

    @Test
    fun `a single-day night has no end date`() {
        scrape("dstrkt-overview.html").single { it.title == "ELYSIUM" }.endDate.shouldBeNull()
    }

    @Test
    fun `keeps the spaces between the runs of a rich-text description`() {
        scrape("dstrkt-overview-edge-cases.html").single { it.title == "SUMMER RAVE" }.description shouldBe
            "Two floors of techno until noon.\nGarden open."
    }

    @Test
    fun `drops a last day before the start`() {
        scrape("dstrkt-overview-edge-cases.html").single { it.title == "SUMMER RAVE" }.endDate.shouldBeNull()
    }

    @Test
    fun `skips a record without a date or a title and keeps one without a ticket link`() {
        val events = scrape("dstrkt-overview-edge-cases.html")

        events.map { it.title } shouldContainExactly listOf("SUMMER RAVE", "OPEN AIR")
        events.single { it.title == "OPEN AIR" }.ticketUrl.shouldBeNull()
    }

    @Test
    fun `returns nothing for a page without the warmup payload`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `returns nothing for a warmup payload without the collection`() {
        val html = """<html><head><script type="application/json" id="wix-warmup-data">{"appsWarmupData":{}}</script></head></html>"""

        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `returns nothing for an unparseable warmup payload`() {
        val html = """<html><head><script type="application/json" id="wix-warmup-data">{not json</script></head></html>"""

        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).shouldBeEmpty()
    }
}
