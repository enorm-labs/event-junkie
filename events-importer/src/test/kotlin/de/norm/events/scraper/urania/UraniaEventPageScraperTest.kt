package de.norm.events.scraper.urania

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
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
 * Unit tests for [UraniaEventPageScraper].
 *
 * Parses static snapshots of Urania `/event/<slug>/` pages for deterministic, offline-safe testing
 * without HTTP fetching — one paid, one free, one free by e-mail booking, one that states no admission at all.
 */
class UraniaEventPageScraperTest {
    private val scraper = UraniaEventPageScraper()

    private fun scrape(
        fixture: String,
        slug: String
    ): ScrapedEvent {
        val sourceUrl = "https://www.urania.de/event/$slug/"
        return scraper.scrape(fixtureDocument("urania/urania-event-$fixture.html", sourceUrl), sourceUrl)!!
    }

    private val opening: ScrapedEvent by lazy { scrape("demokratie-vor-der-wahl", "demokratie-vor-der-wahl") }
    private val memory: ScrapedEvent by lazy { scrape("how-context-modulates-memory", "how-context-modulates-memory-in-flies-and-humans") }
    private val pioneers: ScrapedEvent by lazy { scrape("die-pionierinnen", "die-pionierinnen-der-goldenen-1920er-jahre") }
    private val schoolClass: ScrapedEvent by lazy { scrape("victor-klemperer-tagebuch", "victor-klemperer-tagebuch") }

    @Test
    fun `maps a fully populated event page`() {
        opening.title shouldBe "Demokratie vor der Wahl"
        opening.subtitle shouldBe "bzw.:BEZIEHUNGSWESEN · Podium zur Spielzeiteröffnung"
        opening.eventType shouldBe EventType.READING.name
        opening.eventDate shouldBe LocalDate.of(2026, 9, 3)
        opening.startTime shouldBe LocalTime.of(19, 30)
        opening.sourceId shouldBe "urania:demokratie-vor-der-wahl"
        opening.ticketUrl!! shouldStartWith "https://uraniaberlin.reservix.de/"
    }

    @Test
    fun `reads the lazy-loaded poster, which has no src at all`() {
        opening.imageUrl!! shouldStartWith "https://www.urania.de/wp-content/uploads/"
        memory.imageUrl!! shouldStartWith "https://www.urania.de/wp-content/uploads/"
    }

    @Test
    fun `keeps the intro ahead of the prose, which does not repeat it`() {
        opening.description!! shouldStartWith "Podiumsdiskussion mit Andrea Römmele"
        opening.description!! shouldContain "Drei Tage vor den Landtagswahlen"
    }

    @Test
    fun `reads the full price and keeps the concessions in the note`() {
        // "Eintritt: 8 €, ermäßigt: 5 €, Mitglieder: 3 €" — the first figure is the full price.
        opening.pricePresale shouldBe BigDecimal("8")
        opening.priceNote shouldBe "Eintritt: 8 €, ermäßigt: 5 €, Mitglieder: 3 €"
        opening.free shouldBe false
    }

    @Test
    fun `reads a free evening, which states no figure at all`() {
        // "Eintritt frei: Tickets online buchbar"
        memory.free shouldBe true
        memory.pricePresale.shouldBeNull()
        memory.priceNote!! shouldStartWith "Eintritt frei"
    }

    @Test
    fun `reads a school-class event's free tickets, booked by e-mail`() {
        // "Kostenfreie Tickets: anmeldung@urania-berlin.de", with no "Eintritt" line and no ticket link.
        schoolClass.free shouldBe true
        schoolClass.priceNote shouldBe "Kostenfreie Tickets"
        schoolClass.pricePresale.shouldBeNull()
        schoolClass.ticketUrl.shouldBeNull()
        schoolClass.description!! shouldContain "Kostenfreie Tickets: anmeldung@urania-berlin.de"
    }

    @Test
    fun `states no price where the venue announces none`() {
        pioneers.pricePresale.shouldBeNull()
        pioneers.priceNote.shouldBeNull()
        pioneers.free shouldBe false
    }

    @Test
    fun `stores the billed speakers as headliners`() {
        pioneers.artists.map { it.name } shouldBe listOf("Thomas R. Hoffmann")
        opening.artists.map { it.name }.first() shouldBe "Andrea Römmele"
        opening.artists.map { it.role }.toSet() shouldBe setOf(ArtistRole.HEADLINER)
    }

    // Events 13274 and 13275: the billing line names the audience; 13274 has no format label and was typed a talk.
    @Test
    fun `bills nobody at a school-class screening and types it from the audience line`() {
        val checker = schoolClassScreening("Checker Tobi 3 – Die heimliche Herrscherin der Erde", format = null)
        checker.artists shouldBe emptyList()
        checker.eventType shouldBe EventType.SCREENING.name

        val muppets = schoolClassScreening("Die Muppets Weihnachtsgeschichte", format = "Kino im Humboldtsaal")
        muppets.artists shouldBe emptyList()
        muppets.eventType shouldBe EventType.SCREENING.name
    }

    private fun schoolClassScreening(
        title: String,
        format: String?
    ): ScrapedEvent {
        val sourceUrl = "https://www.urania.de/event/school-class/"
        val html =
            """
            <span class="c-event-article_content_group">HOMO LUDENS</span>
            ${format?.let { "<h6 class=\"c-event-article_content_format\">$it</h6>" }.orEmpty()}
            <h1 class="c-event-article_content_title o-h1">$title</h1>
            <p class="c-event-article_content_artist"><strong>Weihnachtskino für Schulklassen</strong></p>
            <h4 class="c-event-article_content_date-time"><strong>Di, 15.12.2026</strong> | 10:00 Uhr</h4>
            <div class="c-text-box"><p><strong>Kostenfreie Tickets für Schulklassen:</strong> anmeldung@example.de</p></div>
            """.trimIndent()
        return scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)!!
    }

    @Test
    fun `reads the date and clock out of the page's one date line`() {
        // "Do, 03.09.2026 | 19:30 Uhr"
        pioneers.eventDate shouldBe LocalDate.of(2026, 9, 17)
        pioneers.startTime shouldBe LocalTime.of(17, 30)
    }

    @Test
    fun `finds the admission line by its label, not by a euro sign`() {
        admissionLineOf("Foo bar. Eintritt: 8 €, ermäßigt: 5 €. Foto: X") shouldBe "Eintritt: 8 €, ermäßigt: 5 €"
        admissionLineOf("Eintritt frei: Tickets online buchbar") shouldBe "Eintritt frei: Tickets online buchbar"
        admissionLineOf("Für Schulklassen ab Klassenstufe 9 Kostenfreie Tickets: anmeldung@urania-berlin.de Der Vortrag")
            .shouldBe("Kostenfreie Tickets")
        admissionLineOf("Ein Abend über Geld.").shouldBeNull()
        admissionLineOf(null).shouldBeNull()
    }

    @Test
    fun `returns null for a page without an event heading`() {
        val sourceUrl = "https://www.urania.de/event/demokratie-vor-der-wahl/"
        val document = Jsoup.parse("<html><body><main></main></body></html>", sourceUrl)
        scraper.scrape(document, sourceUrl).shouldBeNull()
    }
}
