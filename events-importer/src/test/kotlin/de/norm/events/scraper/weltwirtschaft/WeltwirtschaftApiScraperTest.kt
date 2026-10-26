package de.norm.events.scraper.weltwirtschaft

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class WeltwirtschaftApiScraperTest {
    private val scraper = WeltwirtschaftApiScraper()

    private fun fixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("scraper/weltwirtschaft/$name")!!
            .bufferedReader()
            .readText()

    private val live = scraper.scrapePage(fixture("weltwirtschaft-events.json")).events
    private val year = scraper.scrapePage(fixture("weltwirtschaft-events-2026.json")).events

    private fun event(slug: String) = year.single { it.sourceId == "weltwirtschaft:$slug" }

    @Test
    fun `keeps the DJ night and drops the seasonal menu and the private bookings`() {
        live.map { it.sourceId } shouldContainExactly listOf("weltwirtschaft:dance-the-oyster-lametta-edition")
    }

    @Test
    fun `reads the monthly DJ night in full`() {
        val night = live.single()

        night.title shouldBe "Dance the Oyster"
        night.subtitle shouldBe "Lametta Edition"
        night.eventType shouldBe EventType.PARTY.name
        night.typeIsFallback shouldBe false
        night.eventDate shouldBe LocalDate.of(2026, 11, 28)
        night.startTime shouldBe LocalTime.of(22, 0)
        night.endDate.shouldBeNull()
        night.endTime.shouldBeNull()
        night.sourceUrl shouldBe "https://weltwirtschaft.berlin/event/dance-the-oyster-lametta-edition/"
        night.ticketUrl shouldBe "https://www.eventbrite.it/e/dance-the-oyster-lametta-edition-2026-tickets-1988543409869"
        night.imageUrl shouldBe "https://weltwirtschaft.berlin/wp-content/uploads/2026/10/Bildschirmfoto-2026-10-11-um-17.49.41.png"
        night.description!! shouldContain "Annika Line Trost"
    }

    @Test
    fun `drops every notice over a year of the calendar`() {
        year shouldHaveSize 16
        val titles = year.map { it.title }
        titles shouldNotContain "Geschlossene Gesellschaft im Restaurant"
        titles shouldNotContain "Berlinale im HKW"
        titles shouldNotContain "Berlin Marathon"
        titles shouldNotContain "Fashion Week Berlin: SELVA HUYGENS bei Weltwirtschaft"
        titles shouldNotContain "⚽ WM Live"
        titles shouldNotContain "Geänderte Öffnungszeiten 🥵 am Sonntag"
    }

    @Test
    fun `takes an all-day night's start from its title`() {
        val night = event("dance-the-oysterdjane-annika-line-trost-ab-2200")

        night.startTime shouldBe LocalTime.of(22, 0)
        night.endDate.shouldBeNull()
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "Annika Line Trost", role = "DJ"))
    }

    @Test
    fun `keeps an end time within the night`() {
        val night = event("dance-the-oysterhoch-die-haende-ferienende")

        night.startTime shouldBe LocalTime.of(21, 0)
        night.endDate shouldBe LocalDate.of(2026, 8, 23)
        night.endTime shouldBe LocalTime.of(2, 0)
    }

    @Test
    fun `reads no DJ from a night that names none`() {
        event("dance-the-oyster-into-the-summer").artists.shouldBeEmpty()
    }

    @Test
    fun `drops a brunch, which is out of scope`() {
        val json =
            """{"events":[{"id":1,"slug":"christmas-brunch","start_date":"2026-11-29 11:00:00","end_date":"2026-11-29 15:00:00",""" +
                """"all_day":false,"title":"Christmas Brunch Around the World","description":"","url":"https://weltwirtschaft.berlin/event/christmas-brunch/"}]}"""

        scraper.scrapePage(json).events.shouldBeEmpty()
    }

    @Test
    fun `types a talk by its title and leaves an unmarked dinner as a fallback`() {
        event("talk-show-multiple-crises").subtitle shouldBe "Restaurant"
        val dinner = event("noche-mexicana")
        dinner.eventType shouldBe EventType.OTHER.name
        dinner.typeIsFallback shouldBe true
    }
}
