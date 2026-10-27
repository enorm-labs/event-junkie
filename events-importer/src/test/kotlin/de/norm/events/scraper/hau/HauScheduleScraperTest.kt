package de.norm.events.scraper.hau

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class HauScheduleScraperTest {
    private val url = "https://www.hebbel-am-ufer.de/en/programme/schedule-tickets/"
    private val october = Jsoup.parse(fixture("hau-schedule.html"), url)
    private val november = Jsoup.parse(fixture("hau-schedule-2026-11.html"), url)
    private val december = Jsoup.parse(fixture("hau-schedule-2026-12.html"), url)
    private val hau1 = HauScheduleScraper(HauHouse.HAU1)
    private val hau2 = HauScheduleScraper(HauHouse.HAU2)
    private val hau3 = HauScheduleScraper(HauHouse.HAU3)

    @Test
    fun `each house keeps its own stages and HAU4 belongs to none`() {
        val first = hau1.scrape(october, url)
        val second = hau2.scrape(october, url)
        val third = hau3.scrape(october, url)

        first shouldHaveSize 11
        second shouldHaveSize 13
        third shouldHaveSize 15
        (first + second + third).none { it.title == "FEELINGS" } shouldBe true
        first.all { it.sourceId.startsWith("hau1:") } shouldBe true
        second.all { it.sourceId.startsWith("hau2:") } shouldBe true
        third.all { it.sourceId.startsWith("hau3:") } shouldBe true
        (first + second + third).map { it.sourceId }.toSet() shouldHaveSize 39
    }

    @Test
    fun `reads a concert with its doors time and act`() {
        val event = hau1.scrape(october, url).single { it.title == "SOAP&SKIN" }

        event.subtitle shouldBe "Concert"
        event.eventType shouldBe EventType.CONCERT.name
        event.eventDate shouldBe LocalDate.of(2026, 10, 6)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.room.shouldBeNull()
        event.sourceUrl shouldBe "https://www.hebbel-am-ufer.de/en/programme/pdetail/soapskin-konzert/"
        event.sourceId shouldBe "hau1:soapskin-konzert-2026-10-06-2000"
        event.artists.map { it.name } shouldBe listOf("SOAP&SKIN")
    }

    @Test
    fun `types a WAU DJ set as a party in the WAU room with its DJs`() {
        val event = hau2.scrape(october, url).single { it.subtitle == "DJ set by Kidcat & Broccoli" }

        event.eventType shouldBe EventType.PARTY.name
        event.room shouldBe "WAU"
        event.startTime shouldBe LocalTime.of(22, 0)
        event.artists.map { it.name to it.role } shouldBe listOf("Kidcat" to ArtistRole.DJ, "Broccoli" to ArtistRole.DJ)
    }

    @Test
    fun `types staged works as shows that bill no act, and talks as readings`() {
        val events = hau1.scrape(october, url) + hau2.scrape(october, url)

        events.first { it.title == "She She Pop" }.eventType shouldBe EventType.SHOW.name
        events.first { it.title == "She She Pop" }.artists.shouldBeEmpty()
        events.first { it.title == "Creamcake" }.eventType shouldBe EventType.SHOW.name
        events.single { it.title == "Politics of Heartbreak #3" }.eventType shouldBe EventType.READING.name
        events.single { it.title.startsWith("Gegenmacht") }.eventType shouldBe EventType.READING.name
        events.first { it.title == "Ali Chahrour" }.let {
            it.eventType shouldBe EventType.SHOW.name
            it.typeIsFallback shouldBe true
        }
    }

    @Test
    fun `keeps each performance of a production apart by date and time`() {
        val slots = hau3.scrape(october, url).filter { it.title == "Kubra Khademi and Mortaza Behboudi" }

        slots shouldHaveSize 8
        slots.map { it.sourceId }.toSet() shouldHaveSize 8
        slots.first().sourceId shouldBe "hau3:kubra-khademi-afghan-women-a-desperate-vitality-2026-10-09-1900"
    }

    @Test
    fun `puts the Houseclub rows in their room and drops workshops`() {
        val third = hau3.scrape(november, url)

        third shouldHaveSize 19
        third.filter { it.room == "Houseclub" } shouldHaveSize 6
        third.none { it.title == "machina eX toolkit Release" } shouldBe true
        hau2.scrape(november, url).none { it.title.contains("Tanzworkshop") } shouldBe true
    }

    @Test
    fun `follows the month switcher to the next month and stops after the last`() {
        hau1.nextMonthPage(october) shouldBe
            "https://www.hebbel-am-ufer.de/en/programme/schedule-tickets/prodMonth/11-2026/?cHash=0edaab90db95b38a3c08b5de92f382d9"
        hau1.nextMonthPage(december).shouldBeNull()
        hau1.scrape(december, url).map { it.eventDate } shouldBe
            listOf(LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 18), LocalDate.of(2026, 12, 19), LocalDate.of(2026, 12, 20))
    }

    @Test
    fun `returns nothing for a page without a schedule`() {
        hau1.scrape(Jsoup.parse("<html><body></body></html>", url), url).shouldBeEmpty()
        hau1.nextMonthPage(Jsoup.parse("<html><body></body></html>", url)).shouldBeNull()
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader.getResourceAsStream("scraper/hau/$name")).bufferedReader().readText()
}
