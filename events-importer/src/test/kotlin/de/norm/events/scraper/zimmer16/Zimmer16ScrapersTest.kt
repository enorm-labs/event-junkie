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
        bourbon.imageUrl shouldBe "https://zimmer16.com/wp-json/yesticket/v1/picture/28683"
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
    fun `bills a comma co-bill and the act after mit, but not a release tour or the open stage`() {
        val billed =
            Zimmer16OverviewPageScraper()
                .scrape(Jsoup.parse(cards(PRODUCTION_TITLES), baseUrl), baseUrl)
                .associate { it.title to (it.eventType to it.artists.map { artist -> artist.name to artist.role }) }

        billed["Le Tomboy Release Tour, The Murk Prince"] shouldBe (EventType.CONCERT.name to listOf("The Murk Prince" to "HEADLINER"))
        billed["Soundtrack to Sunset, Landmusik"] shouldBe
            (EventType.CONCERT.name to listOf("Soundtrack to Sunset" to "HEADLINER", "Landmusik" to "HEADLINER"))
        billed["Frühling im Späti mit Jens Ohrenblicker"] shouldBe (EventType.CONCERT.name to listOf("Jens Ohrenblicker" to "HEADLINER"))
        billed["Offene Bühne"] shouldBe (EventType.CONCERT.name to emptyList())
        billed["Die offene Liederbühne – Singer-Songwriter-Forum"] shouldBe (EventType.CONCERT.name to emptyList())
        billed.getValue("Vicki Vomit, Musikalische Lesung mit einem reißerischen Titel, der Zuschauer zieht").second.shouldBeEmpty()
        billed.getValue("Lea Willms and MUD/O").second.map { it.first } shouldBe listOf("Lea Willms", "MUD/O")
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
        enriched.imageUrl shouldBe bourbon.imageUrl
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
    fun `stores a free night at 0 euros but not a pay-what-you-want one`() {
        val lesebuehne = events.first { it.title.startsWith("So noch nie") }

        val free = eventPage.enrich(lesebuehne, Jsoup.parse(yesticketPage("<p>Eintritt frei</p>"), baseUrl)).shouldNotBeNull()
        free.free shouldBe true
        free.priceNote.shouldBeNull()
        val payWhatYouWant = eventPage.enrich(lesebuehne, Jsoup.parse(yesticketPage("<p>Eintritt frei, but pay what you want</p>"), baseUrl)).shouldNotBeNull()
        payWhatYouWant.free shouldBe false
        payWhatYouWant.priceBoxOffice shouldBe lesebuehne.priceBoxOffice
        payWhatYouWant.priceNote shouldBe "Eintritt frei, but pay what you want"
        payWhatYouWant.description shouldBe "Eintritt frei, but pay what you want"
    }

    @Test
    fun `notes only the pay-what-you-want sentence, and only on a 0 euro night`() {
        val lesebuehne = events.first { it.title.startsWith("So noch nie") }
        val text = "<p>Für Künstler ab 19:00 Uhr geöffnet. Eintritt frei, pay what you want. Bringt Texte mit!</p><p>Moderation: Lothar</p>"

        eventPage.enrich(lesebuehne, Jsoup.parse(yesticketPage(text), baseUrl)).shouldNotBeNull().priceNote shouldBe "Eintritt frei, pay what you want."
        val priced = eventPage.enrich(lesebuehne, Jsoup.parse(yesticketPage(text, price = "8.00"), baseUrl)).shouldNotBeNull()
        priced.priceBoxOffice shouldBe BigDecimal("8.00")
        priced.priceNote.shouldBeNull()
    }

    @Test
    fun `yields nothing for an event page without its JSON-LD`() {
        eventPage.enrich(events.first(), Jsoup.parse("<html><body></body></html>", baseUrl)).shouldBeNull()
        Zimmer16OverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun yesticketPage(
        paragraphs: String,
        price: String = "0.00"
    ): String =
        """
        <script type="application/ld+json">
        {"@context": "https://schema.org", "@type": "Event", "startDate": "2026-10-26T19:30:00",
         "offers": {"@type": "Offer", "price": "$price", "priceCurrency": "EUR"}}
        </script>
        <div class="spacer--C">$paragraphs</div>
        """.trimIndent()

    private fun cards(titles: List<String>): String =
        titles.withIndex().joinToString("") { (day, title) ->
            """
            <a href="https://www.yesticket.org/event/de/card-${day + 1}-erwachsene/">
              <div class="ytp-event-card">
                <span class="ytp-event-card-month">Nov.</span><span class="ytp-event-card-day">${day + 1}</span>
                <span class="ytp-event-card-year">2026</span>
                <strong class="ytp-event-card-title">$title (Erwachsene)</strong>
              </div>
            </a>
            """.trimIndent()
        }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/zimmer16/$name")!!
            .bufferedReader()
            .readText()

    private companion object {
        /** Production titles from 2026-10-12 (#3164). */
        val PRODUCTION_TITLES =
            listOf(
                "Le Tomboy Release Tour, The Murk Prince",
                "Soundtrack to Sunset, Landmusik",
                "Frühling im Späti mit Jens Ohrenblicker",
                "Offene Bühne",
                "Die offene Liederbühne – Singer-Songwriter-Forum",
                "Vicki Vomit, Musikalische Lesung mit einem reißerischen Titel, der Zuschauer zieht",
                "Lea Willms and MUD/O"
            )
    }
}
