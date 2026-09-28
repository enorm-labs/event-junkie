package de.norm.events.scraper.modus

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [ModusDetailPageScraper].
 *
 * Parses static snapshots of Modus Berlin `/event/DDMMYY-<Name>` pages for deterministic,
 * offline-safe testing without HTTP fetching.
 */
class ModusDetailPageScraperTest {
    private val scraper = ModusDetailPageScraper()

    private fun scrape(
        fixture: String,
        slug: String
    ): ScrapedEvent {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/modus/modus-detail-$fixture.html")!!
                .bufferedReader()
                .readText()
        val sourceUrl = "https://modus-berlin.de/event/$slug"
        return scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)!!
    }

    @Test
    fun `maps a fully populated detail page`() {
        val c4rl = scrape("c4rl", "240926-c4rl")
        c4rl.title shouldBe "c4rl"
        c4rl.eventType shouldBe EventType.CONCERT.name
        c4rl.eventDate shouldBe LocalDate.of(2026, 9, 24)
        c4rl.startTime shouldBe LocalTime.of(20, 0)
        c4rl.sourceId shouldBe "modus:240926-c4rl"
        c4rl.imageUrl shouldBe "https://modus-berlin.de/img/6a3e6c11430088.95263972.jpg"
        c4rl.ticketUrl shouldBe "https://landstreicher-konzerte.de/konzerte/c4rl-b-26"
        c4rl.description!! shouldContain "c4rl ist ein deutscher"
        c4rl.artists.map { it.name } shouldBe listOf("c4rl")
    }

    @Test
    fun `reads the doors time out of the description prose`() {
        // The venue gives doors no markup of its own — the detail page ends with
        // "Doors: 19:30" / "Start: 20:00" lines inside the description.
        scrape("c4rl", "240926-c4rl").doorsTime shouldBe LocalTime.of(19, 30)
    }

    @Test
    fun `reads the German Einlass spelling of the doors line too`() {
        // c4rl writes "Doors: 19:30"; this one writes "Beginn 20:00 // Einlass 19:00".
        val slam = scrape("poetry-slam", "270826-SpreevomWeizenOpenAir-PoetrySlam-StandUpShow")
        slam.doorsTime shouldBe LocalTime.of(19, 0)
        slam.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `ignores a relocation word buried in the description prose`() {
        // This description says the show "wird ... in den Innenraum verlegt" if it rains — a
        // contingency, not a relocation. Only the title feeds the status.
        val slam = scrape("poetry-slam", "270826-SpreevomWeizenOpenAir-PoetrySlam-StandUpShow")
        slam.description!! shouldContain "verlegt"
        slam.status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `reads the rendered date rather than the stale date in the slug`() {
        val lunaSimao = scrape("luna-simao", "160426-LunaSimao")
        lunaSimao.eventDate shouldBe LocalDate.of(2027, 4, 13)
        lunaSimao.sourceId shouldBe "modus:160426-LunaSimao"
    }

    @Test
    fun `marks a postponed show and strips the note from its title`() {
        val lunaSimao = scrape("luna-simao", "160426-LunaSimao")
        lunaSimao.status shouldBe EventStatus.POSTPONED.name
        lunaSimao.storedTitle() shouldBe "Luna Simao"
        lunaSimao.ticketUrl!! shouldContain "eventim.de"
    }

    @Test
    fun `types a poetry slam as a reading rather than a concert`() {
        scrape("poetry-slam", "270826-SpreevomWeizenOpenAir-PoetrySlam-StandUpShow").eventType shouldBe
            EventType.READING.name
    }

    @Test
    fun `publishes no price for any event`() {
        val c4rl = scrape("c4rl", "240926-c4rl")
        c4rl.pricePresale.shouldBeNull()
        c4rl.priceBoxOffice.shouldBeNull()
        c4rl.soldOut shouldBe false
    }

    @Test
    fun `returns null for a page without a title`() {
        val sourceUrl = "https://modus-berlin.de/event/240926-c4rl"
        val document = Jsoup.parse("<html><body><main></main></body></html>", sourceUrl)
        scraper.scrape(document, sourceUrl).shouldBeNull()
    }
}
