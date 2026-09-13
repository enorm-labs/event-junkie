package de.norm.events.scraper.colosseum

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [ColosseumOverviewPageScraper].
 *
 * Parses a static snapshot of Colosseum's `/event` page (whose events live in the embedded
 * `wix-warmup-data` JSON) for deterministic, offline-safe testing without HTTP fetching. The states
 * the live programme did not happen to show — cancellation, tiered prices, a free event, a missing
 * slug or date — come from the hand-crafted `colosseum-overview-edge-cases.html` variant.
 */
class ColosseumOverviewPageScraperTest {
    private val scraper = ColosseumOverviewPageScraper()
    private val baseUrl = "https://www.colosseumberlin.com/event"

    private val events: List<ScrapedEvent> by lazy { parse("colosseum-overview.html") }
    private val edgeCaseEvents: List<ScrapedEvent> by lazy { parse("colosseum-overview-edge-cases.html") }

    private fun parse(fixture: String): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/colosseum/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    private fun edgeCaseEvent(sourceId: String): ScrapedEvent = edgeCaseEvents.first { it.sourceId == sourceId }

    @Test
    fun `discovers every event in the warmup payload`() {
        events shouldHaveSize 18
    }

    @Test
    fun `maps a fully populated event`() {
        val funke = event("colosseum:cornelia-funke")
        funke.title shouldBe "Cornelia Funke"
        funke.subtitle shouldBe "Buchpremiere  »Emma glaubt nicht an Feen«"
        funke.eventType shouldBe EventType.READING.name
        funke.eventDate shouldBe LocalDate.of(2026, 9, 13)
        funke.startTime shouldBe LocalTime.of(15, 30)
        funke.endDate shouldBe LocalDate.of(2026, 9, 13)
        funke.endTime shouldBe LocalTime.of(17, 0)
        funke.sourceUrl shouldBe "https://www.colosseumberlin.com/details-registrierung/cornelia-funke"
        funke.imageUrl shouldBe "https://static.wixstatic.com/media/fb623f_be9dd581db1b41dd8cfbff934388e18f~mv2.jpg"
        funke.pricePresale shouldBe BigDecimal("20.30")
        funke.priceNote.shouldBeNull()
        funke.ticketUrl.shouldBeNull()
        funke.soldOut shouldBe false
        funke.status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `converts the UTC start instant to the Berlin wall-clock time`() {
        // startDate is 2026-08-26T16:30:00Z; in Europe/Berlin (summer time) that is 18:30 — the
        // announced evening slot, not the 16:30 UTC value.
        val premiere = event("colosseum:philipp-ruch-und-anastasia-tikhomirova-feiern-premiere-im-colosseum")
        premiere.eventDate shouldBe LocalDate.of(2026, 8, 26)
        premiere.startTime shouldBe LocalTime.of(18, 30)
    }

    @Test
    fun `keeps an externally ticketed event on sale and links its shop`() {
        // Wix emits `"soldOut": true` for every event it does not sell tickets for itself, while the
        // page renders a working "Tickets kaufen" button pointing at the promoter's shop.
        val fussball = event("colosseum:der-fussball-mein-leben-ich")
        fussball.soldOut shouldBe false
        fussball.pricePresale.shouldBeNull()
        fussball.priceNote.shouldBeNull()
        fussball.ticketUrl shouldBe "https://shop.11freunde.de/tickets/der-fussball-mein-leben-und-ich-mit-thomas-schaaf.html#"
    }

    @Test
    fun `sets no ticket url when tickets are sold on the wix event page`() {
        event("colosseum:gysis-begegnungen-mit-joe-kaeser").ticketUrl.shouldBeNull()
    }

    @Test
    fun `types the house's own formats from its wording`() {
        // "… - Film: <title>" and the "Kinoevents" series are film nights, a "Buchpremiere" is a
        // reading, and a podcast recorded on stage is a staged show.
        event("colosseum:kalkofes-zeitreise-film-die-abenteuer-des-rabbi-jacob").eventType shouldBe EventType.SCREENING.name
        event("colosseum:investment").eventType shouldBe EventType.SCREENING.name
        event("colosseum:sebastian-becker-und-tara-louise-wittwer").eventType shouldBe EventType.READING.name
        event("colosseum:raum27-1-1").eventType shouldBe EventType.SHOW.name
    }

    @Test
    fun `recovers a reading from the shared Lesung cue`() {
        event("colosseum:annika-sala-interaktive-lesung").eventType shouldBe EventType.READING.name
    }

    @Test
    fun `leaves a talk as OTHER rather than defaulting to concert`() {
        // This is a talks-and-readings house, so a category-less event is not assumed to be a gig.
        event("colosseum:gysis-begegnungen-mit-philipp-hochmair").eventType shouldBe EventType.OTHER.name
    }

    @Test
    fun `collapses the non-breaking spaces a pasted title carries`() {
        event("colosseum:josh-solo-wer-singt-dann-lieder-fur-dich-2").title shouldBe "JOSH. Solo - Wer singt dann Lieder für dich?"
        event("colosseum:babywho-connect").title shouldBe "babywho CONNECT"
    }

    @Test
    fun `derives no artists because the subtitles carry no support billing`() {
        events.forEach { it.artists.shouldBeEmpty() }
    }

    @Test
    fun `leaves the fields the source never publishes empty`() {
        events.forEach {
            it.doorsTime.shouldBeNull()
            it.description.shouldBeNull()
            it.genre.shouldBeNull()
        }
    }

    @Test
    fun `marks a cancelled event`() {
        edgeCaseEvent("colosseum:abgesagte-lesung").status shouldBe EventStatus.CANCELLED.name
    }

    @Test
    fun `marks a sold-out event and notes its price range`() {
        val soldOut = edgeCaseEvent("colosseum:ausverkaufter-abend-mit-raengen")
        soldOut.soldOut shouldBe true
        soldOut.pricePresale shouldBe BigDecimal("12.00")
        soldOut.priceNote shouldBe "€12.00 – €30.00"
    }

    @Test
    fun `keeps a zero-price event at zero so free entry is derived downstream`() {
        edgeCaseEvent("colosseum:eintritt-frei").pricePresale shouldBe BigDecimal("0.00")
    }

    @Test
    fun `skips entries without a slug or a resolvable date`() {
        edgeCaseEvents.map { it.title } shouldBe
            listOf("Abgesagte Lesung", "Ausverkaufter Abend mit Rängen", "Vorverkauf beim Veranstalter", "Eintritt frei")
    }

    @Test
    fun `returns an empty list when the page has no warmup payload`() {
        val document = Jsoup.parse("<html><body></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }

    @Test
    fun `stores no end when the venue hides it`() {
        // Wix fills an end for every event; `endDateHidden: true` is the venue saying it means nothing (ADR-029).
        val free = edgeCaseEvent("colosseum:eintritt-frei")
        free.endDate.shouldBeNull()
        free.endTime.shouldBeNull()
    }
}
