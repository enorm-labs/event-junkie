package de.norm.events.scraper.so36

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [So36DetailPageScraper].
 *
 * Parses static detail-page fixtures for a concert and a party and asserts the
 * per-field extraction, including the concert-only artist roster and the
 * price/ticket fields that parties lack.
 */
class So36DetailPageScraperTest {
    private val scraper = So36DetailPageScraper()

    private val concertUrl =
        "https://www.so36.com/produkte/95201-tickets-poison-ruin-so36-berlin-am-09-07-2026"
    private val partyUrl =
        "https://www.so36.com/produkte/97683-tickets-last-night-so36-berlin-am-03-07-2026"

    private fun fixture(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/so36/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @Test
    fun `parses all fields of a concert detail page`() {
        val event = scraper.scrape(fixture("so36-detail-concert.html", concertUrl), concertUrl)
        event.shouldNotBeNull()

        event.title shouldBe "POISON RUIN"
        event.eventType shouldBe EventType.CONCERT.name
        event.subtitle shouldBe "+ GUM + CLAVV"
        event.eventDate shouldBe LocalDate.of(2026, 7, 9)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.pricePresale shouldBe BigDecimal("22.0")
        event.ticketUrl shouldBe "https://www.greyzone-tickets.de/produkte/1271"
        event.imageUrl.shouldNotBeNull()
        event.imageUrl shouldContain "HP_PoisonRuin"
        event.description.shouldNotBeNull()
        event.description shouldContain "Poison Ruin formed in Philadelphia"
        event.status shouldBe EventStatus.SCHEDULED.name
        event.sourceId shouldBe "so36:95201"
        event.soldOut shouldBe false
    }

    @Test
    fun `extracts the concert lineup with the title as headliner and plus-prefixed support acts`() {
        val event = scraper.scrape(fixture("so36-detail-concert.html", concertUrl), concertUrl)
        event.shouldNotBeNull()

        event.artists shouldHaveSize 3
        event.artists[0].name shouldBe "POISON RUIN"
        event.artists[0].role shouldBe "HEADLINER"
        event.artists.drop(1).map { it.name } shouldBe listOf("GUM", "CLAVV")
        event.artists.drop(1).all { it.role == "SUPPORT" } shouldBe true
    }

    @Test
    fun `reads the acts after a mit billing frame and never the night's name`() {
        // "SADTEMBER mit TAHA, JOHNBOY M.IKARUS" + "+ Arbok 48 + Support": SADTEMBER is the night (#1132).
        val url = "https://www.so36.com/produkte/96647-tickets-sadtember-mit-taha-johnboy-m-ikarus-so36-berlin-am-05-09-2026"
        val event = scraper.scrape(fixture("so36-detail-mit-billing.html", url), url)
        event.shouldNotBeNull()

        event.title shouldBe "SADTEMBER mit TAHA, JOHNBOY M.IKARUS"
        event.artists.map { it.name to it.role } shouldBe
            listOf("TAHA" to "HEADLINER", "JOHNBOY M.IKARUS" to "HEADLINER", "Arbok 48" to "SUPPORT")
    }

    @Test
    fun `parses a party detail page without a lineup, price or ticket link`() {
        val event = scraper.scrape(fixture("so36-detail-party.html", partyUrl), partyUrl)
        event.shouldNotBeNull()

        event.title shouldBe "LAST NIGHT"
        event.eventType shouldBe EventType.PARTY.name
        // A party subtitle is a descriptive tagline, not a "+ …" support line.
        event.subtitle shouldBe "Die Indie-Pop Party"
        event.artists shouldHaveSize 0
        event.pricePresale shouldBe null
        event.ticketUrl shouldBe null
        event.doorsTime shouldBe LocalTime.of(22, 0)
        event.startTime shouldBe LocalTime.of(22, 0)
        event.sourceId shouldBe "so36:97683"
    }

    @Test
    fun `strips role labels and splits multi-act support lines, dropping label-only chunks`() {
        // Build a minimal concert detail page with a messy SO36 support subtitle.
        fun concertWithSubtitle(subtitle: String) =
            Jsoup.parse(
                """
                <html><body>
                  <small class="supertitle">Konzert</small>
                  <h1><span itemprop="name">HEADLINER</span><small class="subtitle">$subtitle</small></h1>
                </body></html>
                """.trimIndent(),
                concertUrl
            )

        fun supports(subtitle: String) =
            scraper
                .scrape(concertWithSubtitle(subtitle), concertUrl)!!
                .artists
                .filter { it.role == "SUPPORT" }
                .map { it.name }

        // Labels are stripped, not captured as part of the name.
        supports("+ Special Guest: FUCK") shouldBe listOf("FUCK")
        supports("+ Support: cosmic joke & bad beat") shouldBe listOf("cosmic joke", "bad beat")
        // "und"/"and" split per boundary: the leading act separates, but a backing-band
        // tail ("& The Sun Band") stays attached rather than becoming its own artist.
        supports("+ Earth Tongue und Scott Hepple & The Sun Band") shouldBe
            listOf("Earth Tongue", "Scott Hepple & The Sun Band")
        // A bare label with no act name is dropped entirely rather than becoming an artist.
        supports("+ div. Supports") shouldBe emptyList()
        supports("+ Support") shouldBe emptyList()
        // An event-segment label (aftershow slot) is not a performer and is dropped.
        supports("+ ACID AFTERSHOW") shouldBe emptyList()
    }

    @Test
    fun `returns null when the page has no event title`() {
        val html = "<html><body><div>Not a product page</div></body></html>"
        scraper.scrape(Jsoup.parse(html, concertUrl), concertUrl) shouldBe null
    }
}
