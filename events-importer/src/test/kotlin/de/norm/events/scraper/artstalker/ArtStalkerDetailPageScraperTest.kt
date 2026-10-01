package de.norm.events.scraper.artstalker

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
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/artstalker/artstalker-detail-$name.html")!!
                .bufferedReader()
                .readText()
        ),
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
    fun `returns null for a page without an event`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>"), "https://art-stalker.reservix.de/tickets-x/e1").shouldBeNull()
    }
}
