package de.norm.events.scraper.madmonkeyroom

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class MadMonkeyRoomShopPageScraperTest {
    private val baseUrl = "https://t.rausgegangen.de/tickets/shop/mad-monkey-2"
    private val scraper = MadMonkeyRoomShopPageScraper()

    private val events by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/madmonkeyroom/madmonkeyroom-shop.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    @Test
    fun `reads every dated show at the club`() {
        events shouldHaveSize 460
        events.map { it.sourceId }.distinct() shouldHaveSize 460
        events.all { it.eventType == EventType.COMEDY.name } shouldBe true
        events.minOf { it.eventDate } shouldBe LocalDate.of(2026, 10, 16)
        events.maxOf { it.eventDate } shouldBe LocalDate.of(2027, 12, 30)
    }

    @Test
    fun `maps one show fully`() {
        val show = events.single { it.sourceId == "mad_monkey_room:mad-monkey-die-show-419" }

        show.title shouldBe "Mad Monkey - Die Show"
        show.eventDate shouldBe LocalDate.of(2026, 10, 16)
        show.startTime shouldBe LocalTime.of(20, 30)
        show.sourceUrl shouldBe "https://t.rausgegangen.de/tickets/mad-monkey-die-show-419"
        show.ticketUrl shouldBe show.sourceUrl
        show.pricePresale shouldBe BigDecimal("23.00")
        show.imageUrl shouldBe
            "https://imageflow.rausgegangen.de/url/https%3A//s3.eu-central-1.amazonaws.com/rausgegangen/QNYoKnBMTpucOtjFUh4w_mmrshowblank1.jpg" +
            "?width=1200&height=900&mode=crop"
        show.artists.shouldBeEmpty()
    }

    @Test
    fun `reads the cheapest ticket of a from price`() {
        val tuesday = events.single { it.sourceId == "mad_monkey_room:der-mad-monkey-dienstag-178" }

        tuesday.pricePresale shouldBe BigDecimal("15.00")
        tuesday.startTime shouldBe LocalTime.of(20, 30)
    }

    @Test
    fun `bills the comedian of a solo programme`() {
        val solo = events.single { it.sourceId == "mad_monkey_room:philipp-leinenbach-unqualifiziert-aber-sexy" }

        solo.title shouldBe "Philipp Leinenbach - \"Unqualifiziert, aber sexy\""
        solo.artists shouldContainExactly listOf(ScrapedArtist(name = "Philipp Leinenbach", role = "HEADLINER"))
    }

    @Test
    fun `skips the undated voucher and the cruise that boards elsewhere`() {
        val ids = events.map { it.sourceId }

        ids shouldNotContain "mad_monkey_room:gutschein-mad-monkey-room-1"
        ids shouldNotContain "mad_monkey_room:mad-monkey-cruise-comedy-auf-der-spree-15"
    }

    @Test
    fun `returns nothing for a shop without items`() {
        scraper.scrape(Jsoup.parse("<div class=\"ui items\"></div>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
