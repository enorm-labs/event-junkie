package de.norm.events.scraper.artstalker

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class ArtStalkerDetailPageScraperTest {
    private val scraper = ArtStalkerDetailPageScraper()

    private fun scrape(
        name: String,
        id: String
    ) = scraper.scrape(
        fixtureDocument("artstalker/artstalker-detail-$name.html"),
        "https://art-stalker.reservix.de/tickets-$name/e$id"
    )

    @Test
    fun `reads presale and box office and cuts the blurb before the practical block`() {
        val event = scrape("patlansky", "2535642").shouldNotBeNull()
        event.title shouldBe "Dan Patlansky"
        event.subtitle shouldBe "Blues Rock"
        event.sourceId shouldBe "art_stalker:2535642"
        event.eventDate shouldBe LocalDate.of(2026, 10, 3)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.pricePresale shouldBe BigDecimal("19")
        event.priceBoxOffice shouldBe BigDecimal("23")
        event.free shouldBe false
        event.imageUrl shouldBe "https://cdn.reservix.com/core/img/event/detailEvent_2535642.jpg"
        val description = event.description.shouldNotBeNull()
        description shouldStartWith "Auf Platte ist Dan Patlansky"
        description shouldEndWith "The Blues can not be played in any other way.–Dan Patlansky –"
        description shouldNotContain "Fotos:"
        description shouldNotContain "https://"
        description shouldNotContain "Liebe Eltern"
    }

    @Test
    fun `a free night reads doors and no price`() {
        val event = scrape("bandstand", "2556952").shouldNotBeNull()
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.free shouldBe true
        event.pricePresale.shouldBeNull()
        event.priceBoxOffice.shouldBeNull()
    }

    @Test
    fun `an unlabelled ticket price is both prices, and free entry for players is not a free night`() {
        val event = scrape("session", "2560217").shouldNotBeNull()
        event.doorsTime shouldBe LocalTime.of(18, 30)
        event.pricePresale shouldBe BigDecimal("7")
        event.priceBoxOffice shouldBe BigDecimal("7")
        event.free shouldBe false
        event.description.shouldNotBeNull() shouldEndWith "Jeden Dienstag · 19:30 Uhr · ART Stalker."
    }

    @Test
    fun `reads an event page on reservix de like one on the shop`() {
        val event =
            scraper
                .scrape(
                    fixtureDocument("artstalker/artstalker-detail-blackmail.html"),
                    "https://www.reservix.de/tickets-blackmail-rhythm-and-blues-rock-in-berlin-art-stalker-am-5-12-2026/e2556994"
                ).shouldNotBeNull()
        event.title shouldBe "Blackmail"
        event.eventDate shouldBe LocalDate.of(2026, 12, 5)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.pricePresale shouldBe BigDecimal("16")
        event.description.shouldNotBeNull() shouldStartWith "Die Band steht für"
    }

    @Test
    fun `a page that states no price leaves the shop's cheapest ticket`() {
        val page =
            Jsoup.parse(
                """
                <h1>Kiezpoeten Slam Show</h1><time class="c-event-date" datetime="2026-12-17T19:30:00"></time>
                <div class="c-ticket-fan__price-info"><div>Tickets ab 14,67 € <sup>*</sup></div></div>
                """.trimIndent()
            )
        val event = scraper.scrape(page, "https://art-stalker.reservix.de/tickets-x/e1").shouldNotBeNull()
        event.pricePresale shouldBe BigDecimal("14.67")
        event.priceBoxOffice.shouldBeNull()
        event.free shouldBe false
    }

    @Test
    fun `reads an exhibition's run from the heading as its end date (#5588)`() {
        val page =
            Jsoup.parse(
                """
                <h1>Ausstellung: Face it &amp; own it! - Lars Koepke &amp; Vincent J. E. Sieck - 26.11.2026 - 09.01.2027</h1>
                <time class="c-event-date" datetime="2026-11-26T19:00:00"></time>
                """.trimIndent()
            )
        val event = scraper.scrape(page, "https://www.reservix.de/tickets-x/e2580338").shouldNotBeNull()
        event.title shouldBe "Ausstellung: Face it & own it!"
        event.subtitle shouldBe "Lars Koepke & Vincent J. E. Sieck"
        event.endDate shouldBe LocalDate.of(2027, 1, 9)
        scrape("patlansky", "2535642").shouldNotBeNull().endDate.shouldBeNull()
    }

    @Test
    fun `returns null for a page without an event`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>"), "https://art-stalker.reservix.de/tickets-x/e1").shouldBeNull()
    }
}
