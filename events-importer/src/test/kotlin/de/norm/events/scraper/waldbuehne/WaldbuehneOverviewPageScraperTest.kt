package de.norm.events.scraper.waldbuehne

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class WaldbuehneOverviewPageScraperTest {
    private val url = "https://www.waldbuehne-berlin.de/programm-und-tickets/"
    private val events =
        WaldbuehneOverviewPageScraper().scrape(fixtureDocument("waldbuehne/waldbuehne-programm.html", url), url)

    @Test
    fun `reads every date but the orchestra concert`() {
        events shouldHaveSize 20
        events.none { it.title.contains("Philharmoniker") } shouldBe true
    }

    @Test
    fun `maps a concert from its JSON-LD`() {
        val bocelli = events.first { it.title == "Andrea Bocelli" }

        bocelli.subtitle shouldBe "Romanza World Tour 2027"
        bocelli.eventType shouldBe "CONCERT"
        bocelli.eventDate shouldBe LocalDate.of(2027, 5, 15)
        bocelli.startTime shouldBe LocalTime.of(20, 0)
        bocelli.sourceUrl shouldBe "https://www.waldbuehne-berlin.de/event/andrea_bocelli_2027-05-15_20/"
        bocelli.sourceId shouldBe "waldbuehne:andrea_bocelli_2027-05-15_20"
        bocelli.imageUrl.shouldNotBeNull() shouldStartWith "https://www.waldbuehne-berlin.de/site/assets/files/"
        bocelli.pricePresale shouldBe BigDecimal("119.99")
        bocelli.priceNote shouldBe "119.99 – 259.99 EUR"
        bocelli.artists.map { it.name } shouldContainExactly listOf("Andrea Bocelli")
    }

    @Test
    fun `reads a midnight clock as no start time`() {
        events.first { it.title == "blink-182" }.startTime.shouldBeNull()
    }

    @Test
    fun `marks a sold-out offer`() {
        events.filter { it.title == "Herbert Grönemeyer" }.map { it.soldOut } shouldContainExactly listOf(true, true)
        events.first { it.title == "Clueso" }.soldOut shouldBe false
    }

    @Test
    fun `decodes an escaped ampersand in the name`() {
        events.filter { it.title == "Dieter Thomas Kuhn & Band" } shouldHaveSize 3
    }

    @Test
    fun `bills the names a tour line lists in place of the show title`() {
        val summit = events.first { it.title == "DAS GIPFELTREFFEN" }

        summit.doorsTime shouldBe LocalTime.of(17, 0)
        summit.artists.map { it.name } shouldContainExactly listOf("Olaf Schubert", "Torsten Sträter", "Johann König")
        summit.artists.map { it.role }.distinct() shouldContainExactly listOf(ArtistRole.HEADLINER)
    }

    @Test
    fun `reads no cast from a tour line`() {
        castOf("Live 2027").shouldBeNull()
        castOf("UNSER SOMMERMOMENT  - Open Air 2027").shouldBeNull()
        castOf("DAS GROSSE FINALE").shouldBeNull()
    }

    @Test
    fun `returns nothing for a page without JSON-LD`() {
        WaldbuehneOverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", url), url).shouldBeEmpty()
    }
}
