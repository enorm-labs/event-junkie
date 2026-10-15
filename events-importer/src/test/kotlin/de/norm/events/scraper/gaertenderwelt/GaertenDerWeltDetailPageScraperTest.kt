package de.norm.events.scraper.gaertenderwelt

import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Unit tests for [GaertenDerWeltDetailPageScraper], parsing saved snapshots of the park's
 * `events2` single view (captured 6 August 2026; the EPIC SYMPHONY, Mega 90er and Stereoact pages
 * on 15 October 2026).
 *
 * The concert snapshot is the one that exercises every labelled prose paragraph the park writes —
 * doors, both prices, promoter and support billing — and that the description keeps none of them.
 */
class GaertenDerWeltDetailPageScraperTest {
    private val scraper = GaertenDerWeltDetailPageScraper()

    private fun fixture(
        name: String,
        sourceUrl: String
    ): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/gaertenderwelt/$name")!!
                .bufferedReader()
                .readText(),
            sourceUrl
        )

    @Test
    fun `lifts every labelled paragraph out of a concert page`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-concert.html", CONCERT_URL), CONCERT_URL).shouldNotBeNull()

        event.title shouldBe "Agnes Obel"
        event.doorsTime shouldBe LocalTime.of(17, 30)
        event.pricePresale shouldBe BigDecimal("60.00")
        event.priceBoxOffice shouldBe BigDecimal("65")
        event.priceNote shouldBe "ab 60,00 € Abendkasse: ab 65 €"
        event.promoters shouldContainExactly listOf("Loft Concert GmbH")
        // The support billing joins the subtitle in the shared "Support: …" form.
        event.subtitle shouldBe "Support: Peter Gregson"
        event.free shouldBe false
        event.sourceId shouldBe "gaerten_der_welt:2026-08-15_1900/agnes-obel"
        event.ticketUrl?.startsWith("https://www.eventim.de/event/agnes-obel-gaerten-der-welt-21055531/") shouldBe true
        event.imageUrl shouldBe
            "https://www.gaertenderwelt.de/fileadmin/_processed_/f/0/csm_gdw_events_AgnesObel_2025__Alex_Bruel_Flagstad___1__c32ece05a8.png"
    }

    @Test
    fun `keeps the metadata paragraphs out of the description`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-concert.html", CONCERT_URL), CONCERT_URL).shouldNotBeNull()
        val description = event.description.shouldNotBeNull()

        description shouldContain "Die dänische Ausnahmekünstlerin"
        description shouldNotContain "Einlass"
        description shouldNotContain "Abendkasse"
        description shouldNotContain "Loft Concert GmbH"
        description shouldNotContain "Peter Gregson"
    }

    @Test
    fun `leaves the date unresolved so the listing stamp decides`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-concert.html", CONCERT_URL), CONCERT_URL).shouldNotBeNull()

        // The page renders a year-less "Samstag, 15.08." — the URL stamp is the real date.
        event.eventDate shouldBe UNRESOLVED_EVENT_DATE
        event.startTime.shouldBeNull()
        event.eventType.shouldBeNull()
    }

    @Test
    fun `parses a single-price page and keeps its pricing note`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-quiz.html", QUIZ_URL), QUIZ_URL).shouldNotBeNull()

        event.title shouldBe "Quiz Night Show - Gärten der Welt"
        event.pricePresale shouldBe BigDecimal("18")
        event.priceBoxOffice.shouldBeNull()
        event.priceNote shouldBe "18 € inkl. Parkeintritt bei Eintritt ab 17 Uhr"
        event.doorsTime shouldBe LocalTime.of(17, 0)
        event.ticketUrl shouldBe "https://gruen-berlin.ticketfritz.de/Event/Kalender/25204/56433?typ=Vorlage"
    }

    @Test
    fun `reads a price the park labelled Kosten rather than Tickets`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-concert-kosten.html", KOSTEN_URL), KOSTEN_URL).shouldNotBeNull()

        event.title shouldBe "KARAT live in der Arena"
        event.pricePresale shouldBe BigDecimal("47.00")
        event.priceNote shouldBe "Tickets ab 47,00€"
        event.promoters shouldContainExactly listOf("MB-Konzerte GmbH")
    }

    @Test
    fun `does not price a free event at the park ticket it names on top`() {
        val html =
            fixture("gaertenderwelt-detail-concert-kosten.html", KOSTEN_URL)
                .outerHtml()
                .replace("Tickets ab 47,00€", "die Veranstaltung ist kostenfrei (zzgl. Parkeintritt: ab 17.00 Uhr gilt unser Feierabend-Ticket für 5,50 Euro)")
        val event = scraper.scrape(Jsoup.parse(html, KOSTEN_URL), KOSTEN_URL).shouldNotBeNull()

        event.pricePresale.shouldBeNull()
        event.priceNote shouldBe "die Veranstaltung ist kostenfrei (zzgl. Parkeintritt: ab 17.00 Uhr gilt unser Feierabend-Ticket für 5,50 Euro)"
    }

    @Test
    fun `reads a doors time the park wrote without minutes`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-concert-kosten.html", KOSTEN_URL), KOSTEN_URL).shouldNotBeNull()

        // The page says "Einlass: 18 Uhr", where the concert page says "ab 17:30 Uhr".
        event.doorsTime shouldBe LocalTime.of(18, 0)
    }

    @ParameterizedTest(name = "{0} gives {1}")
    @CsvSource(
        "16.30 Uhr, 16:30",
        "16:30 Uhr, 16:30",
        "ab 17:30 Uhr, 17:30",
        "18 Uhr, 18:00",
        "'16.30, Haupteingang', ",
        "'ab 09.07.2027', ",
        nullValues = [""]
    )
    fun `reads the doors time after a colon or a dot, and never from inside a number`(
        einlass: String,
        doors: String?
    ) {
        val html =
            fixture("gaertenderwelt-detail-concert-kosten.html", KOSTEN_URL)
                .outerHtml()
                .replace("<strong>Einlass: </strong>18 Uhr", "<strong>Einlass: </strong>$einlass")
        val event = scraper.scrape(Jsoup.parse(html, KOSTEN_URL), KOSTEN_URL).shouldNotBeNull()

        event.doorsTime shouldBe doors?.let(LocalTime::parse)
    }

    @Test
    fun `takes the doors time rather than the start time when the page lists both on one line`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-quiz.html", QUIZ_URL), QUIZ_URL).shouldNotBeNull()

        event.doorsTime shouldBe LocalTime.of(17, 0)
    }

    @Test
    fun `drops the contact mailbox from the description`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-quiz.html", QUIZ_URL), QUIZ_URL).shouldNotBeNull()

        event.description.shouldNotBeNull() shouldNotContain "besucherzentrum@gaertenderwelt.de"
    }

    @Test
    fun `parses a page that names no price, no doors time and no promoter`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-exhibition.html", EXHIBITION_URL), EXHIBITION_URL).shouldNotBeNull()

        event.title shouldBe "Zwischen Himmel und Erde: Ausstellung"
        event.subtitle shouldBe "Ausstellung der Frauen-Kunst-Karawane"
        event.pricePresale.shouldBeNull()
        event.doorsTime.shouldBeNull()
        event.ticketUrl.shouldBeNull()
        event.promoters shouldContainExactly emptyList()
        // An absent price is unknown, not free.
        event.free shouldBe false
        event.description.shouldNotBeNull() shouldContain "zehn Künstlerinnen"
    }

    @Test
    fun `reads a promoter line that follows the price on the Tickets paragraph`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-epic-symphony.html", EPIC_URL), EPIC_URL).shouldNotBeNull()

        event.promoters shouldContainExactly listOf("Ovation Events GmbH")
        event.pricePresale shouldBe BigDecimal("42.00")
        event.priceBoxOffice.shouldBeNull()
        // The promoter and its Kontakt line no longer ride along in the pricing note.
        event.priceNote shouldBe "ab 42,00 € (Hinweis: Gültig nur ab offiziellem Einlass, kein Tagesticket für die Gärten der Welt)"
        event.doorsTime shouldBe LocalTime.of(18, 0)
    }

    @Test
    fun `reads a promoter line that follows the doors time on the Einlass paragraph`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-stereoact.html", STEREOACT_URL), STEREOACT_URL).shouldNotBeNull()

        event.promoters shouldContainExactly listOf("Semmel Concerts Entertainment GmbH")
        event.doorsTime shouldBe LocalTime.of(18, 0)
        event.pricePresale shouldBe BigDecimal("39.90")
        event.description.shouldNotBeNull() shouldNotContain "Semmel"
    }

    @Test
    fun `reads a promoter line below two Einlass lines and keeps the first of them`() {
        val event = scraper.scrape(fixture("gaertenderwelt-detail-mega-90er.html", MEGA_90ER_URL), MEGA_90ER_URL).shouldNotBeNull()

        // "HB Music, Hendrik Büring": the part after the comma is the contact, as on the Loft Concert line.
        event.promoters shouldContainExactly listOf("HB Music")
        event.pricePresale shouldBe BigDecimal("29.95")
        // "Einlass Gärten der Welt: 16.30 Uhr" opens the paragraph and wins over the later
        // "Einlass Veranstaltungsbereich: 17 Uhr"; the dot between hours and minutes is read (#3353).
        event.doorsTime shouldBe LocalTime.of(16, 30)
    }

    @Test
    fun `starts no field inside an unlabelled prose paragraph`() {
        val html =
            fixture("gaertenderwelt-detail-concert-kosten.html", KOSTEN_URL)
                .outerHtml()
                .replace("Die Berliner Kultband KARAT", "Einleitung<br>Veranstalter: kein Name<br>Die Berliner Kultband KARAT")
        val event = scraper.scrape(Jsoup.parse(html, KOSTEN_URL), KOSTEN_URL).shouldNotBeNull()

        event.promoters shouldContainExactly listOf("MB-Konzerte GmbH")
    }

    @Test
    fun `returns null for a page with no single-view block`() {
        val notFound = Jsoup.parse("<html><body><h1>404</h1></body></html>", CONCERT_URL)

        scraper.scrape(notFound, CONCERT_URL).shouldBeNull()
    }

    @Test
    fun `returns null for a detail URL that carries no date stamp`() {
        val unstamped = "https://www.gaertenderwelt.de/events/veranstaltungen/detail/agnes-obel/"

        scraper.scrape(fixture("gaertenderwelt-detail-concert.html", unstamped), unstamped).shouldBeNull()
    }

    private companion object {
        private const val CONCERT_URL = "https://www.gaertenderwelt.de/events/veranstaltungen/detail/2026-08-15_1900/agnes-obel/"
        private const val QUIZ_URL =
            "https://www.gaertenderwelt.de/events/veranstaltungen/detail/2026-10-03_1730/quiz-night-show-gaerten-der-welt/"
        private const val EXHIBITION_URL =
            "https://www.gaertenderwelt.de/events/veranstaltungen/detail/2026-09-01_0900/zwischen-himmel-und-erde-ausstellung/"
        private const val KOSTEN_URL = "https://www.gaertenderwelt.de/events/veranstaltungen/detail/2026-09-12_1900/karat-live-in-berlin/"
        private const val EPIC_URL =
            "https://www.gaertenderwelt.de/events/veranstaltungen/detail/2027-06-11_1930/epic-symphony-live-orchestra-choir-dj/"
        private const val MEGA_90ER_URL =
            "https://www.gaertenderwelt.de/events/veranstaltungen/detail/2027-07-09_1700/das-mega-90er-live-open-air-27/"
        private const val STEREOACT_URL =
            "https://www.gaertenderwelt.de/events/veranstaltungen/detail/2027-05-05_1930/stereoact-ich-liebe-das-leben-party-2027-feat-lena-marie-engel/"
    }
}
