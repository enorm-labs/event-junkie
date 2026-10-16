package de.norm.events.scraper.bkatheater

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

class BkaTheaterSpielplanPageScraperTest {
    private val scraper = BkaTheaterSpielplanPageScraper()
    private val url = "https://www.bka-theater.de/spielplan/"

    private val events by lazy {
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/bkatheater/bkatheater-spielplan.html")!!
                    .bufferedReader()
                    .readText(),
                url
            ),
            url
        )
    }

    @Test
    fun `reads every performance but the dark evenings and the children's shows`() {
        events shouldHaveSize 244
        events.map { it.title } shouldNotContain "Keine Abendvorstellung"
        events.none { it.title == "Platypus" } shouldBe true
        events.map { it.sourceId }.distinct() shouldHaveSize events.size
        events.last().eventDate shouldBe LocalDate.of(2027, 11, 17)
    }

    @Test
    fun `reads one performance in full`() {
        val show = events.first { it.sourceId == "bka_theater:4218" }

        show.title shouldBe "Friedemann Weise: Satire suchen ein Zuhause"
        show.subtitle shouldBe null
        show.eventType shouldBe "COMEDY"
        show.typeIsFallback shouldBe false
        show.genre shouldBe "Comedy"
        show.eventDate shouldBe LocalDate.of(2026, 10, 16)
        show.startTime shouldBe LocalTime.of(20, 0)
        show.sourceUrl shouldBe "https://www.bka-theater.de/prg/462"
        show.ticketUrl shouldBe "https://bka-theater-webshop.comfortticket.de/de/veranstaltung/0d60aa6d-479d-4320-8251-af131be95f9c/4750"
        show.imageUrl shouldBe
            "https://www.bka-theater.de/bilder/cache/9faa3c73/friedemann-weise-satire-suchen-ein-zuhause-foto-marvin-ruppert-rgb-orig.jpg"
        show.pricePresale shouldBe BigDecimal("24.2")
        show.soldOut shouldBe false
        show.artists shouldContainExactly listOf(ScrapedArtist(name = "Friedemann Weise", titleDerived = true))
    }

    @Test
    fun `takes the shop price only for the performances the JSON-LD lists`() {
        events.count { it.pricePresale != null } shouldBe 30
        events.first { it.title == "Die große BKA Spendengala" }.pricePresale shouldBe BigDecimal("44")
    }

    @Test
    fun `reads a red status dot as sold out`() {
        // The captured page's red dots all sit on children's shows, which are skipped.
        val row =
            """
            <div class="row bka-spielplan-row event-wrapper" data-pid="16" data-tid="4374">
              <span class="h5 event-date" data-eventdate="20261110">10.11.</span>
              <a href="https://example.org/tickets"><span title="Tickets Ausverkauft"><i class="bi bi-circle-fill bka-color-rot"></i></span>
              <span class="event-time" data-eventtime="2000">20:00</span></a>
              <a class="bka-nodeco event-title" href="/prg/16?tid=4374"><h2>Chaos Royal:</h2><h3>IMPROTANIA</h3></a>
            </div>
            """.trimIndent()

        val show = scraper.scrape(Jsoup.parse(row, url), url).single()

        show.soldOut shouldBe true
        events.none { it.soldOut } shouldBe true
    }

    @Test
    fun `splits a co-billed heading into its acts`() {
        val show = events.first { it.sourceId == "bka_theater:4384" }

        show.title shouldBe "Rachel Intervention & Yvonne Nightstand: Café Verklatscht"
        show.subtitle shouldBe null
        show.eventType shouldBe "SHOW"
        show.artists.map { it.name } shouldContainExactly listOf("Rachel Intervention", "Yvonne Nightstand")
    }

    @Test
    fun `bills the programme line of a house series`() {
        val concert = events.first { it.sourceId == "bka_theater:4397" }

        concert.title shouldBe "Unerhörte Musik: Duo Amoeba"
        concert.eventType shouldBe "CONCERT"
        concert.genre shouldBe "Zeitgenössisches Konzert"
        concert.artists.map { it.name } shouldContainExactly listOf("Duo Amoeba")
    }

    @Test
    fun `bills nobody for a production heading without a colon`() {
        val gala = events.first { it.title == "Die große BKA Spendengala" }

        gala.eventType shouldBe "SHOW"
        gala.artists.shouldBeEmpty()
    }

    @Test
    fun `keeps a guest note as the subtitle and leaves the broadcast badge out of the genre`() {
        val show = events.first { it.sourceId == "bka_theater:4361" }

        show.subtitle shouldBe "Talkgäst*in: Klaus Wowereit"
        show.genre shouldBe "Kochshow"
    }

    @Test
    fun `falls back to a show when no badge names the format`() {
        val slam = events.first { it.sourceId == "bka_theater:4455" }

        slam.startTime shouldBe null
        slam.typeIsFallback shouldBe true
        slam.eventType shouldBe "SHOW"
    }

    @Test
    fun `drops a presenting verb and an unnamed company from the acts`() {
        bkaActs("Bob Schneider präsentiert", "JUTTAS FUTSCHI FREIZEIT", "", "COMEDY").map { it.name } shouldContainExactly
            listOf("Bob Schneider")
        bkaActs("Denis Fischer und Band", "A Tribute to Johnny Cash", "", "CONCERT").map { it.name } shouldContainExactly
            listOf("Denis Fischer")
        bkaActs("INTERVENTION BERLIN", "Rachel Intervention & Friends", "", "SHOW").map { it.name } shouldContainExactly
            listOf("Rachel Intervention")
    }

    @Test
    fun `splits a versus bill into both acts`() {
        bkaActs("Chaos Royal versus Die Gorillas", "Das theatrale Osterduell", "", "COMEDY").map { it.name } shouldContainExactly
            listOf("Chaos Royal", "Die Gorillas")
    }

    @Test
    fun `bills nobody for a house series heading on its own`() {
        bkaActs(null, null, "Unerhörte Musik", "CONCERT").shouldBeEmpty()
        bkaActs(null, null, "LEOPOLD", "CONCERT").map { it.name } shouldContainExactly listOf("LEOPOLD")
    }

    @Test
    fun `maps the house formats to event types`() {
        bkaEventType(listOf("Improvisationstheater")) shouldBe "COMEDY"
        bkaEventType(listOf("Musikkabarett & Lesung")) shouldBe "COMEDY"
        bkaEventType(listOf("Polit-Talk")) shouldBe "READING"
        bkaEventType(listOf("Leseshow")) shouldBe "READING"
        bkaEventType(listOf("Neuköllnical")) shouldBe "SHOW"
        bkaEventType(listOf("Releasekonzert")) shouldBe "CONCERT"
        bkaEventType(listOf("Eine Veranstaltung der DRK Kliniken Berlin")) shouldBe null
    }
}
