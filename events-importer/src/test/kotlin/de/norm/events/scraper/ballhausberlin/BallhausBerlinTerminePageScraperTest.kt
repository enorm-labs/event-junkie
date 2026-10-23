package de.norm.events.scraper.ballhausberlin

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** `/de/termine/` saved on 2026-10-23, cut to the dates from September 2026 on: 94 of its 510. */
fun ballhausBerlinTermine() =
    Jsoup.parse(
        BallhausBerlinTerminePageScraperTest::class.java.classLoader.getResourceAsStream("scraper/ballhausberlin/ballhausberlin-termine.html")!!,
        null,
        BALLHAUS_BERLIN_URL
    )

const val BALLHAUS_BERLIN_URL = "https://ballhaus-berlin.de/de/termine/"

class BallhausBerlinTerminePageScraperTest {
    private val events = BallhausBerlinTerminePageScraper().scrape(ballhausBerlinTermine(), BALLHAUS_BERLIN_URL)

    private fun event(id: String) = events.single { it.sourceId == "ballhaus_berlin:$id" }

    @Test
    fun `reads every public date and leaves out the private parties, the market and the choir`() {
        events shouldHaveSize 77
        events.filter { it.title.startsWith("Geschlossene") || it.title.startsWith("Vintage") || it.title.startsWith("Yes Choir") }.shouldBeEmpty()
        events.map { it.sourceId }.toSet().size shouldBe events.size
    }

    @Test
    fun `reads a dance band's night with doors, start, both prices and the image`() {
        val band = event("406__datum_2")

        band.title shouldBe "Die Paveier"
        band.eventType shouldBe EventType.CONCERT.name
        band.eventDate shouldBe LocalDate.of(2027, 6, 16)
        band.doorsTime shouldBe LocalTime.of(19, 0)
        band.startTime shouldBe LocalTime.of(20, 0)
        band.pricePresale shouldBe BigDecimal("35")
        band.priceBoxOffice shouldBe BigDecimal("40")
        band.imageUrl!! shouldStartWith "https://ballhaus-berlin.de/terminbilder/"
        band.sourceUrl shouldBe BALLHAUS_BERLIN_URL
    }

    @Test
    fun `reads the subtitle, the ticket link and the text`() {
        val jazz = event("512__datum_5")

        jazz.subtitle shouldBe "Berlin‘s Unique Jazz Party"
        jazz.eventType shouldBe EventType.PARTY.name
        jazz.ticketUrl shouldBe "https://www.eventbrite.de/e/jazz-n-beats-tickets-2003469318664"
        jazz.description!! shouldStartWith "Music from all Jazz influenced genres"
    }

    @Test
    fun `types the nights by the house's words`() {
        event("397__datum_24").eventType shouldBe EventType.SHOW.name
        event("389__datum_54").eventType shouldBe EventType.PARTY.name
        event("504__datum_2").eventType shouldBe EventType.PARTY.name
    }

    @Test
    fun `drops an info folgen subtitle`() {
        event("517__datum_2").subtitle.shouldBeNull()
    }
}
