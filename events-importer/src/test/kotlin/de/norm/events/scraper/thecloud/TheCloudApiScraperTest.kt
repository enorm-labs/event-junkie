package de.norm.events.scraper.thecloud

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class TheCloudApiScraperTest {
    private val scraper = TheCloudApiScraper()
    private val page =
        scraper.scrapePage(
            javaClass.classLoader
                .getResourceAsStream("scraper/thecloud/thecloud-events.json")!!
                .bufferedReader()
                .readText()
        )

    private fun event(slug: String) = page.events.single { it.sourceId == "the_cloud:$slug" }

    @Test
    fun `reads every event of the one page`() {
        page.events shouldHaveSize 11
        page.nextPageUrl.shouldBeNull()
    }

    @Test
    fun `maps a club night with its image and a clean ticket link`() {
        val night = event("russian-glamour-festival-4")

        night.title shouldBe "RUSSIAN GLAMOUR FESTIVAL"
        night.eventType shouldBe EventType.PARTY.name
        night.eventDate shouldBe LocalDate.of(2026, 10, 24)
        night.startTime shouldBe LocalTime.of(23, 0)
        night.doorsTime.shouldBeNull()
        night.endDate shouldBe LocalDate.of(2026, 10, 25)
        night.endTime shouldBe LocalTime.of(5, 0)
        night.sourceUrl shouldBe "https://thecloud.berlin/event/russian-glamour-festival-4/"
        night.imageUrl shouldBe "https://thecloud.berlin/wp-content/uploads/2026/09/IMG_5995.png"
        night.description!! shouldStartWith "24.10.26 ★ RUSSIAN GLAMOUR FESTIVAL ★"
        night.artists.shouldBeEmpty()
    }

    @Test
    fun `an unmarked night is a party`() {
        event("afrovibes-is-back-in-berlin").eventType shouldBe EventType.PARTY.name
        event("les-biens-halloween-flinta-only").eventType shouldBe EventType.PARTY.name
    }

    @Test
    fun `strips the tracking parameters from a ticket link and decodes its entities`() {
        event("le-shuuk-exklusiv-und-erstmalig-in-berlin").ticketUrl shouldBe "https://leshuuk.ticket.io/w00DPSSW/"
        event("enca-live-on-stage-%e2%98%85-club-the-cloud-berlin").ticketUrl shouldBe
            "https://www.eventbrite.de/e/211126-enca-live-on-stage-club-the-cloud-berlin-dj-bouana-dj-j-c-tickets-2003359066898?aff=oddtdtcreator"
        event("12-12-26-ran-d-berlin").ticketUrl shouldBe "https://wanted-berlin.com/"
        event("afrovibes-is-back-in-berlin").ticketUrl.shouldBeNull()
    }

    @Test
    fun `a live show is a concert of the act its title bills`() {
        event("live-on-stage-%d0%b2%d1%83%d0%b8%d0%bd%d0%b4%d0%bb%d0%b8%d1%8f").artists.map { it.name } shouldContainExactly listOf("ВУИНДЛИЯ")
        event("nairobi-nights-praesentiert-rayvanny-live-in-berlin").artists.map { it.name } shouldContainExactly listOf("RAYVANNY")
        val enca = event("enca-live-on-stage-%e2%98%85-club-the-cloud-berlin")
        enca.eventType shouldBe EventType.CONCERT.name
        enca.artists.map { it.name } shouldContainExactly listOf("ENCA")
    }

    @Test
    fun `an API time the prose calls the doors is the doors, and the event clock is the start`() {
        val night = event("le-shuuk-exklusiv-und-erstmalig-in-berlin")

        night.doorsTime shouldBe LocalTime.of(18, 0)
        night.startTime shouldBe LocalTime.of(19, 0)
        night.endTime shouldBe LocalTime.of(22, 0)
        night.promoters shouldContainExactly listOf("WorldClubDome")
    }

    @Test
    fun `a last-entry time in the prose does not move the start`() {
        val night = event("officialkevents-berlin-kpop-khiphop-night-in-november-%f0%9f%8e%83halloween-edition%f0%9f%8e%83")

        night.title shouldBe "OfficialKEvents | BERLIN: KPOP & KHIPHOP Night in November – 🎃HALLOWEEN EDITION🎃"
        night.doorsTime.shouldBeNull()
        night.startTime shouldBe LocalTime.of(23, 0)
    }

    @Test
    fun `skips an event without a title or a start`() {
        val json =
            """
            {"events": [{"slug": "a", "title": "", "url": "https://thecloud.berlin/event/a/", "start_date": "2026-11-15 23:00:00"},
                        {"slug": "b", "title": "B", "url": "https://thecloud.berlin/event/b/"}]}
            """.trimIndent()

        scraper.scrapePage(json).events.shouldBeEmpty()
    }

    @Test
    fun `an unparseable body is an empty last page`() {
        val result = scraper.scrapePage("<html>")

        result.events.shouldBeEmpty()
        result.nextPageUrl.shouldBeNull()
    }
}
