package de.norm.events.scraper.zimmer16

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class Zimmer16ScrapersTest {
    private val baseUrl = "https://zimmer16.com/"
    private val events = Zimmer16OverviewPageScraper().scrape(Jsoup.parse(fixture("zimmer16-home.html"), baseUrl), baseUrl)
    private val eventPage = Zimmer16EventPageScraper()

    @Test
    fun `reads the homepage's 20 cards with their year and drops the audience from the title`() {
        events shouldHaveSize 20
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 8)
        events.last().eventDate shouldBe LocalDate.of(2026, 10, 31)
        events.forEach { it.title shouldNotContain "(Erwachsene)" }
        val bourbon = events.first { it.title == "Claude Bourbon" }
        bourbon.sourceId shouldBe "zimmer_16:claude-bourbon-erwachsene-10-10-26"
        bourbon.sourceUrl shouldBe "https://www.yesticket.org/event/de/claude-bourbon-erwachsene-10-10-26"
        bourbon.eventType shouldBe EventType.CONCERT.name
        bourbon.artists.map { it.name } shouldBe listOf("Claude Bourbon")
    }

    @Test
    fun `moves a support act out of the title and types the house's other formats`() {
        val rotlicht = events.first { it.title == "Rotlicht" }
        rotlicht.subtitle shouldBe "Support: Isolisation"
        rotlicht.artists.map { it.name to it.role } shouldBe listOf("Rotlicht" to "HEADLINER", "Isolisation" to "SUPPORT")
        events.first { it.title.startsWith("So noch nie") }.eventType shouldBe EventType.READING.name
        events.first { it.title.contains("Kreisler Revue") }.eventType shouldBe EventType.SHOW.name
    }

    @Test
    fun `bills a bare name or a plus co-bill and nobody for a title with a tagline`() {
        events.first { it.title == "he is tall + The Sirens" }.artists.map { it.name } shouldBe listOf("he is tall", "The Sirens")
        events.first { it.title.startsWith("Calum Baird") }.artists.shouldBeEmpty()
        events.first { it.title.startsWith("Kaléko") }.artists.shouldBeEmpty()
        events.first { it.title.startsWith("Bossa Nova Duo") }.artists.shouldBeEmpty()
    }

    @Test
    fun `adds the time, the end, the price and the full text from the event page`() {
        val bourbon = events.first { it.title == "Claude Bourbon" }

        val enriched = eventPage.enrich(bourbon, Jsoup.parse(fixture("yesticket-claude-bourbon.html"), bourbon.sourceUrl)).shouldNotBeNull()

        enriched.startTime shouldBe LocalTime.of(20, 0)
        enriched.endDate shouldBe LocalDate.of(2026, 10, 10)
        enriched.endTime shouldBe LocalTime.of(22, 0)
        enriched.priceBoxOffice shouldBe BigDecimal("14.00")
        enriched.soldOut shouldBe false
        enriched.imageUrl.shouldNotBeNull() shouldStartWith "https://cdn.yesticket.org/"
        val text = enriched.description.shouldNotBeNull()
        text shouldStartWith "Es ist nicht einfach"
        text shouldContain "spanischer Gitarre."
        text shouldNotContain "Hinweis"
    }

    @Test
    fun `marks a sold-out night`() {
        val zillmer = events.first { it.title == "Arno Zillmer" }

        eventPage.enrich(zillmer, Jsoup.parse(fixture("yesticket-arno-zillmer.html"), zillmer.sourceUrl)).shouldNotBeNull().soldOut shouldBe true
    }

    @Test
    fun `yields nothing for an event page without its JSON-LD`() {
        eventPage.enrich(events.first(), Jsoup.parse("<html><body></body></html>", baseUrl)).shouldBeNull()
        Zimmer16OverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/zimmer16/$name")!!
            .bufferedReader()
            .readText()
}
