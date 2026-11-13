package de.norm.events.scraper.ballhauswedding

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class BallhausWeddingScrapersTest {
    private val baseUrl = "https://www.ballhauswedding.de/veranstaltungen"
    private val today = LocalDate.of(2026, 10, 8)
    private val document = fixtureDocument("ballhauswedding/ballhauswedding-veranstaltungen.html", baseUrl)
    private val events = BallhausWeddingOverviewPageScraper().scrape(document, baseUrl, today)

    @Test
    fun `reads every upcoming entry and dates the year-less programme into 2027`() {
        events.size shouldBe 118
        events.first().eventDate shouldBe today
        events.last().eventDate shouldBe LocalDate.of(2027, 6, 27)
        events.map { it.eventDate }.filter { it.year == 2027 }.min() shouldBe LocalDate.of(2027, 1, 6)
        events.forEach { it.sourceId shouldStartWith "ballhaus_wedding:" }
    }

    @Test
    fun `reads a concert's act, time and prices`() {
        val odessa = events.first { it.eventDate == today }
        odessa.title shouldBe "Berlin - Odessa - Express"
        odessa.startTime shouldBe LocalTime.of(19, 30)
        odessa.eventType shouldBe EventType.CONCERT.name
        odessa.pricePresale shouldBe BigDecimal("23")
        odessa.priceBoxOffice shouldBe BigDecimal("27")
        odessa.sourceUrl shouldBe "https://www.ballhauswedding.de/details-registrierung/berlin-odessa-express"
        odessa.artists.shouldBeEmpty()

        events.first { it.title == "Alice Francis" }.artists.map { it.name } shouldBe listOf("Alice Francis")
        events.first { it.title == "Hotel de Pologne" }.artists.shouldBeEmpty()
    }

    @Test
    fun `types the house's formats from the title`() {
        fun typeOf(prefix: String) = events.first { it.title.startsWith(prefix) }.eventType
        typeOf("Tango - die Ballhaus Milonga") shouldBe EventType.PARTY.name
        typeOf("Seniorendiskothek") shouldBe EventType.PARTY.name
        typeOf("Wedding Slam Royale") shouldBe EventType.READING.name
        typeOf("Theatersport Berlin") shouldBe EventType.SHOW.name
        typeOf("Ausgerechnet Wolkenkratzer") shouldBe EventType.SCREENING.name
        typeOf("Christmas Swing") shouldBe EventType.CONCERT.name
        typeOf("Tag des offenen Ballhauses") shouldBe EventType.OTHER.name
    }

    @Test
    fun `joins a title that runs on into the next paragraph and keeps contact lines out of the text`() {
        val lecture = events.first { it.eventDate == LocalDate.of(2026, 10, 12) }
        lecture.title shouldBe "Wenn das gesamte Wissen der Welt im Handy des Kindes steckt - Lecture"
        lecture.eventType shouldBe EventType.READING.name
        lecture.pricePresale shouldBe BigDecimal("39.90")

        val bachata = events.first { it.title == "Bachata-Nacht" }
        bachata.startTime shouldBe LocalTime.of(20, 30)
        bachata.description.shouldNotBeNull() shouldNotContain "@"
        events.first { it.title.startsWith("Theatersport") }.description shouldBe
            "Theatersport Berlin – unser Name ist Programm: wir haben uns mit Leib & Seele dieser ganz besonderen Form von " +
            "Improvisationstheater verschrieben. www.theatersport-berlin.de"
        bachata.pricePresale shouldBe BigDecimal("15")

        val brunch = events.first { it.title.startsWith("Sonntagsbrunch") }
        brunch.startTime shouldBe LocalTime.of(13, 0)
        brunch.endTime shouldBe LocalTime.of(15, 0)
    }

    @Test
    fun `reads a tiered price, free entry and a box-office-only night`() {
        val silvester = events.first { it.title.startsWith("Silvesterball") }
        silvester.pricePresale shouldBe BigDecimal("145")
        events.first { it.title.startsWith("Herbstball") }.let {
            it.pricePresale shouldBe BigDecimal("33")
            it.priceBoxOffice shouldBe BigDecimal("35")
        }
        events.first { it.title.startsWith("Musethica") }.free shouldBe true
        events.first { it.title == "Discofox-Nacht" }.priceBoxOffice shouldBe BigDecimal("20")
        events.first { it.title.startsWith("Tango") }.let {
            it.pricePresale.shouldBeNull()
            it.priceBoxOffice shouldBe BigDecimal("10")
            it.description shouldBe "feinste Tangomusik von DJamila"
        }
    }

    @Test
    fun `dates a stale month block by its weekdays, outvotes a misprinted one and marks the cancelled entry`() {
        events.first { it.title.startsWith("Silvesterball") }.eventDate shouldBe LocalDate.of(2026, 12, 31)

        val september = BallhausWeddingOverviewPageScraper().scrape(document, baseUrl, LocalDate.of(2026, 9, 1))
        val slam = september.first { it.title == "Wedding Slam Royale - KI-Slam" }
        slam.eventDate shouldBe LocalDate.of(2026, 9, 20)
        september.first { it.eventDate == LocalDate.of(2026, 9, 23) }.status shouldBe EventStatus.CANCELLED.name
        september.first { it.eventDate == LocalDate.of(2026, 9, 24) }.status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `a bare note cancels the entry before it, as the page of 2026-11-13 prints it`() {
        val page = fixtureDocument("ballhauswedding/ballhauswedding-veranstaltungen-2026-11-13.html", baseUrl)
        val november = BallhausWeddingOverviewPageScraper().scrape(page, baseUrl, LocalDate.of(2026, 11, 12))

        november.first { it.title == "Russisch Roulette - Krimi - Theater" }.status shouldBe EventStatus.CANCELLED.name
        november.first { it.title == "Vertigo" }.status shouldBe EventStatus.SCHEDULED.name
        november.filter { it.status == EventStatus.CANCELLED.name }.map { it.title } shouldBe listOf("Russisch Roulette - Krimi - Theater")
    }

    @Test
    fun `a colon note cancels the next entry and a bare note the one before it, at either end of a block`() {
        fun statuses(vararg paragraphs: String): List<String> {
            val html = (listOf("November") + paragraphs).joinToString("") { "<p>$it</p>" }
            val page = Jsoup.parse("<div data-testid=\"richTextElement\">$html</div>", baseUrl)
            return BallhausWeddingOverviewPageScraper().scrape(page, baseUrl, LocalDate.of(2026, 11, 1)).map { it.status.take(1) }
        }
        val a = "Do 12.11. 19:30 Uhr A"
        val b = "Fr 13.11. 20 Uhr B"
        val c = "Sa 14.11. 20 Uhr C"

        statuses("leider abgesagt:", a, b, c) shouldBe listOf("C", "S", "S")
        statuses(a, "leider abgesagt", b, c) shouldBe listOf("C", "S", "S")
        statuses("leider abgesagt", a, b) shouldBe listOf("C", "S")
        statuses(a, b, c, "leider abgesagt") shouldBe listOf("S", "S", "C")
        statuses(a, b, "leider abgesagt:") shouldBe listOf("S", "S")
        statuses(a, "leider abgesagt", b, "leider abgesagt", c) shouldBe listOf("C", "C", "S")
        statuses("leider abgesagt:", a, "leider abgesagt:", b, c) shouldBe listOf("C", "C", "S")
    }

    @Test
    fun `adds the end, the poster and the full text from the Wix Events page`() {
        val listing = BallhausWeddingOverviewPageScraper().scrape(document, baseUrl, LocalDate.of(2026, 10, 1)).first()
        val page = fixtureDocument("ballhauswedding/ballhauswedding-event-barbra-streisand.html", listing.sourceUrl)

        val enriched = BallhausWeddingEventPageScraper().enrich(listing, page).shouldNotBeNull()

        enriched.title shouldBe "Vilma Remezaite singt Barbra Streisand"
        enriched.endTime shouldBe LocalTime.of(22, 0)
        enriched.endDate shouldBe LocalDate.of(2026, 10, 1)
        enriched.imageUrl.shouldNotBeNull() shouldStartWith "https://static.wixstatic.com/media/"
        enriched.description.shouldNotBeNull() shouldStartWith "Vilma Remzaite singt die Songs"
        enriched.description.shouldNotBeNull().lines() shouldContain "Am Piano wird sie begleitet vom wunderbaren Pianisten Sebastian Kommerell. 🎹"
        enriched.pricePresale shouldBe BigDecimal("23")
    }

    @Test
    fun `yields nothing for a page without the programme or the JSON-LD`() {
        val empty = Jsoup.parse("<html><body></body></html>", baseUrl)
        BallhausWeddingOverviewPageScraper().scrape(empty, baseUrl, today).shouldBeEmpty()
        BallhausWeddingEventPageScraper().enrich(events.first(), empty).shouldBeNull()
    }
}
