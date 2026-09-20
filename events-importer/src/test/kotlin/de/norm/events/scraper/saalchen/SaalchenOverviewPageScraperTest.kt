package de.norm.events.scraper.saalchen

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [SaalchenOverviewPageScraper].
 *
 * Parses a static snapshot of the Holzmarkt site's shared `/kalender` page for deterministic,
 * offline-safe testing without HTTP fetching. The fixture keeps every row, including the other
 * Holzmarkt locations, so the venue filter is exercised on real data.
 */
class SaalchenOverviewPageScraperTest {
    private val scraper = SaalchenOverviewPageScraper()
    private val baseUrl = "https://www.holzmarkt.com/kalender"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/saalchen/saalchen-kalender.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `keeps only the rows staged at this venue`() {
        // The calendar carries 13 rows across Säälchen, Holzmarkt 25 and the Marktplatz.
        events shouldHaveSize 9
        events.any { it.title == "Jimmy Sax" } shouldBe true
        events.none { it.title.contains("FLOHMARKT") } shouldBe true
        events.none { it.title.contains("BREAKFAST") } shouldBe true
    }

    @Test
    fun `maps a fully populated row`() {
        val frytz = event("saalchen:frytz-tour-2026")
        frytz.title shouldBe "frytz - TOUR 2026"
        frytz.eventType shouldBe EventType.CONCERT.name
        frytz.eventDate shouldBe LocalDate.of(2026, 11, 27)
        frytz.doorsTime shouldBe LocalTime.of(19, 0)
        frytz.startTime shouldBe LocalTime.of(20, 0)
        frytz.pricePresale shouldBe BigDecimal("36.95")
        frytz.priceNote shouldBe "36,95€"
        // `.event-category` is a staging format, so it types the event but is not stored as a genre.
        frytz.genre shouldBe null
        frytz.sourceUrl shouldBe "https://www.holzmarkt.com/veranstaltung/frytz-tour-2026"
        frytz.ticketUrl!! shouldStartWith "https://landstreicher-konzerte.de/"
        frytz.imageUrl!! shouldStartWith "https://www.holzmarkt.com/sites/default/files/"
        frytz.artists.map { it.name } shouldBe listOf("frytz")
    }

    @Test
    fun `converts the UTC calendar timestamp to the Berlin date`() {
        // atc_date_start is 2026-11-14 19:00:00 UTC, i.e. 20:00 Berlin on the 14th.
        event("saalchen:voodoo-juergens-und-die-ansa-panier-live").eventDate shouldBe LocalDate.of(2026, 11, 14)
    }

    @Test
    fun `prefers the labelled prose over the venue's inconsistent time field`() {
        // The ".doors" span reads 20:00 here, but the notice labels Einlass 19 Uhr / Beginn 20 Uhr.
        val voodoo = event("saalchen:voodoo-juergens-und-die-ansa-panier-live")
        voodoo.doorsTime shouldBe LocalTime.of(19, 0)
        voodoo.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `reads a bare-hour time spelling`() {
        // "Einlass: 19 Uhr" / "Beginn: 20 Uhr" — no minutes given.
        event("saalchen:voodoo-juergens-und-die-ansa-panier-live").doorsTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `ignores a parenthetical aside after the start time`() {
        // "Beginn: 18:00 Uhr (Beginn der Vorentscheidung um 15:30 Uhr)".
        val mainEvent = event("saalchen:main-event-4-floor-2026-whacking-festival")
        mainEvent.startTime shouldBe LocalTime.of(18, 0)
        mainEvent.doorsTime shouldBe LocalTime.of(17, 30)
    }

    @Test
    fun `converts a single price and keeps the venue's wording`() {
        val jimmySax = event("saalchen:jimmy-sax")
        // "€40 + fees" — the amount precedes the euro sign here.
        jimmySax.pricePresale shouldBe BigDecimal("40")
        jimmySax.priceNote shouldBe "€40 + fees"
    }

    @Test
    fun `refuses to guess a number from a tiered price`() {
        // "15€ ermäßigt … 25€ Normalpreis 35€ Förderticket" — taking the first would store the
        // concession price as the ticket price, so only the note is kept.
        val stegreif = event("saalchen:stegreif-orchester-freeeroica-1")
        stegreif.pricePresale.shouldBeNull()
        stegreif.priceNote!! shouldContain "Normalpreis"
    }

    @Test
    fun `types an event from the venue category and falls back to the title`() {
        event("saalchen:jimmy-sax").eventType shouldBe EventType.CONCERT.name
        // "Kunst & Kultur" is not a known category, so the title decides: this one is a party.
        event("saalchen:opening-party-4-floor-2026-whacking-festival").eventType shouldBe EventType.PARTY.name
    }

    @Test
    fun `stores no description when the venue writes none, which is most rows`() {
        // The AddToCalendar payload is metadata-only for most Säälchen events; the Holzmarkt 25
        // market rows do carry prose, but those are filtered out.
        events.filterNot { it.sourceId.contains("k-indie") }.forEach { it.description.shouldBeNull() }
    }

    @Test
    fun `reads the festival's end time, day price and line-up sentence`() {
        // One `<p>` block states `Ende: 22:00` and `Eintritt: Tagesticket: 13 € / 2-Tagesticket: 20 €`,
        // and the prose names the four acts after `mit:` (#1584).
        val festival = events.single { it.sourceId.contains("k-indie") }
        festival.doorsTime shouldBe LocalTime.of(18, 0)
        festival.startTime shouldBe LocalTime.of(19, 0)
        festival.endTime shouldBe LocalTime.of(22, 0)
        festival.endDate shouldBe festival.eventDate
        festival.pricePresale shouldBe BigDecimal("13")
        festival.priceNote shouldBe "Tagesticket: 13 € / 2-Tagesticket: 20 €"
        festival.artists.map { it.name } shouldBe listOf("Catch The Young", "Bongjeingan", "kimseungjoo", "Chang Kiha")
        festival.description!! shouldContain "Musik verbindet"
    }

    @Test
    fun `reads the notice block even when the venue leaves it unwrapped`() {
        // Jimmy Sax's description is not wrapped in a paragraph at all, so a <p>-scoped lookup
        // would find no times or price for it.
        val jimmySax = event("saalchen:jimmy-sax")
        jimmySax.doorsTime shouldBe LocalTime.of(19, 30)
        jimmySax.startTime shouldBe LocalTime.of(20, 30)
    }

    @Test
    fun `returns an empty list for a calendar with no rows at this venue`() {
        val document = Jsoup.parse("<html><body><div class='view-content'></div></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}
