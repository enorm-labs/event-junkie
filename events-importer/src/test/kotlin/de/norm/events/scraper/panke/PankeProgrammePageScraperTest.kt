package de.norm.events.scraper.panke

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [PankeProgrammePageScraper].
 *
 * Parses a static snapshot of Panke Culture's `/programme/` page for deterministic, offline-safe
 * testing without HTTP fetching. The fixture keeps **both** lists the page renders — 9 upcoming
 * events and 20 past ones — so the upcoming-only scoping is exercised on real data.
 */
class PankeProgrammePageScraperTest {
    private val scraper = PankeProgrammePageScraper()
    private val sourceUrl = "https://www.pankeculture.com/programme/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/panke/panke-programme.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `reads the venue's upcoming list and leaves its past one alone`() {
        // The page renders 29 articles from one template; only the 9 under UPCOMING EVENTS count.
        events shouldHaveSize 9
        events.map { it.eventDate }.min() shouldBe LocalDate.of(2026, 8, 5)
        events.none { it.eventDate < LocalDate.of(2026, 8, 5) } shouldBe true
    }

    @Test
    fun `maps a fully populated article`() {
        val openMic = event("panke:17378")
        openMic.title shouldBe ">>Who got da Props?!?<< (Open Mic with DJ BOOM BAP)"
        openMic.eventDate shouldBe LocalDate.of(2026, 8, 5)
        openMic.startTime shouldBe LocalTime.of(19, 0)
        openMic.sourceUrl shouldBe sourceUrl
        openMic.imageUrl!! shouldStartWith "https://www.pankeculture.com/wp-content/uploads/"
        openMic.description!! shouldContain "Sketch Corner"
    }

    @Test
    fun `identifies an event by its WordPress post id, there being no per-event page`() {
        events.map { it.sourceId }.toSet() shouldHaveSize 9
        events.map { it.sourceUrl }.toSet() shouldBe setOf(sourceUrl)
        events.none { it.eventDate == UNRESOLVED_EVENT_DATE } shouldBe true
    }

    @Test
    fun `reads the clock out of the venue's prose, with or without seconds`() {
        // "starting at 19:00." and "starting at 23:00:00." both appear.
        event("panke:17378").startTime shouldBe LocalTime.of(19, 0)
        event("panke:17224").startTime shouldBe LocalTime.of(23, 0)
        events.none { it.startTime == null } shouldBe true
    }

    @Test
    fun `takes the clock after the starting-at label, not the run's closing date`() {
        // "on the 13th of August until the 1st of January starting at 20:00:00."
        val fest = event("panke:17337")
        fest.eventDate shouldBe LocalDate.of(2026, 8, 13)
        fest.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `types what the title unambiguously names`() {
        // The venue states no type at all, so an unmistakable title keyword decides first.
        event("panke:17348").eventType shouldBe EventType.OTHER.name // Code Green x Panke Markt
        event("panke:17365").eventType shouldBe EventType.PARTY.name // (c)rave
    }

    @Test
    fun `types a night that bills DJs as a club night`() {
        // The title says nothing, but the venue links a Resident Advisor profile per act.
        event("panke:17227").eventType shouldBe EventType.PARTY.name // EEE
        event("panke:17224").eventType shouldBe EventType.PARTY.name // CALENTURA VINYL ALL STARS
        event("panke:17314").eventType shouldBe EventType.PARTY.name // NOFUTURE 6 YEARS
        events.count { it.eventType == EventType.PARTY.name } shouldBe 4
    }

    @Test
    fun `leaves an event with neither signal untyped`() {
        // A series name is not a format, so these stay OTHER rather than being guessed a concert.
        event("panke:17378").eventType shouldBe EventType.OTHER.name
        events.count { it.eventType == EventType.OTHER.name } shouldBe 5
    }

    @Test
    fun `types a title that names a live show as a concert`() {
        listOf(
            "SOULFUL SKA DANCE MIT LIVE BANDS",
            "Herr Kuchen – Süß und Salzig Tour",
            "Sadat X & Grand Agent Live at Panke"
        ).forEach { title -> withClue(title) { articleTitled(title).eventType shouldBe EventType.CONCERT.name } }
    }

    @Test
    fun `leaves a series title with no live word untyped`() {
        articleTitled("Disko International").eventType shouldBe EventType.OTHER.name
        // `Deliverance` holds `live` only inside a word, which is not a live show.
        articleTitled("Deliverance Night").eventType shouldBe EventType.OTHER.name
    }

    private fun articleTitled(title: String): ScrapedEvent {
        val html =
            """
            <div class="et_pb_events_0"><article id="post-1">
              <div class="event-content-wrapper" data-date="2026-10-17"><h2 class="entry-title">$title</h2></div>
            </article></div>
            """.trimIndent()
        return scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl).single()
    }

    @Test
    fun `does not recognise the venue's abbreviated festival`() {
        // "LA MONA SONIC EXPLORATION FEST" is a festival, but the shared title signal requires the
        // whole word — German "Fest" means a celebration, so widening it would mistype ordinary
        // parties across every venue.
        event("panke:17337").eventType shouldBe EventType.OTHER.name
    }

    @Test
    fun `reads the lineup only from Resident Advisor links`() {
        val eee = event("panke:17227")
        eee.artists.map { it.name } shouldBe listOf("Ziúr", "bela", "ALEX WANG", "Kilo Vee")
        eee.artists.map { it.role }.toSet() shouldBe setOf("DJ")
        // "Dj Chiro" is billed in the same list but not linked, so it is not stored.
        eee.artists.none { it.name.contains("Chiro") } shouldBe true
    }

    @Test
    fun `stores no artists where the venue links none`() {
        // Free prose with no shared convention; a guess would be worse than nothing.
        event("panke:17378").artists.shouldBeEmpty()
        event("panke:17337").artists.shouldBeEmpty()
        events.count { it.artists.isNotEmpty() } shouldBe 3
    }

    @Test
    fun `stores no set time on the one real timetable night, which links no DJ`() {
        // (c)rave: "Timetable<br>23:00-00:00 sixstar<br>…", and not one Resident Advisor link.
        event("panke:17365").artists.shouldBeEmpty()
    }

    @Test
    fun `times the linked DJs from a timetable and adds nobody it names beside them`() {
        // Made-up markup in the page's shape: no real night yet carries both links and a timetable.
        val html =
            """
            <div class="et_pb_events_0"><article id="1">
              <div class="event-content-wrapper" data-date="2026-10-02"></div>
              <h2 class="entry-title">Klubnacht</h2>
              <div class="post-content-full"><div class="et_pb_column_3_4">
                <p>LINE UP: <a href="https://ra.co/dj/sixstar">sixstar</a>, <a href="https://ra.co/dj/tryce">tryce</a></p>
                <p>Timetable<br>23:00-00:00 sixstar<br>00:00-01:30 tryce<br>01:30-02:30 z0 screening</p>
              </div></div>
            </article></div>
            """.trimIndent()
        val night = scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl).single()

        night.artists.map { it.name } shouldBe listOf("sixstar", "tryce")
        night.artists[0].setStart shouldBe Instant.parse("2026-10-02T21:00:00Z")
        night.artists[0].setEnd shouldBe Instant.parse("2026-10-02T22:00:00Z")
        // "00:00-01:30" is past midnight, on Saturday.
        night.artists[1].setStart shouldBe Instant.parse("2026-10-02T22:00:00Z")
        night.artists[1].setEnd shouldBe Instant.parse("2026-10-02T23:30:00Z")
    }

    @Test
    fun `counts a DJ once even when the venue links them twice`() {
        val anniversary = event("panke:17314")
        anniversary.artists.map { it.name } shouldHaveSize
            anniversary.artists
                .map { it.name }
                .toSet()
                .size
        anniversary.artists.map { it.name } shouldBe
            listOf("Some Guest", "Dj Hidrataccioni", "PAULAH", "Paulawar", "entecaliente")
    }

    @Test
    fun `stores a tiered entry line as its lowest figure and keeps the tiers in the note`() {
        val crave = event("panke:17365")
        crave.priceBoxOffice shouldBe BigDecimal("15")
        crave.pricePresale.shouldBeNull()
        crave.priceNote shouldBe "ENTRY15€/18€/20€/25€" // <strong>ENTRY</strong>15€…, flattened
    }

    @Test
    fun `reads a door figure from every cost line and nothing from an event without one`() {
        // "cost 15 euro", "Cost: 12 euro", "Cost 15 euro" and the tiered ENTRY line; the rest print none.
        events.associate { it.sourceId to it.priceBoxOffice } shouldBe
            mapOf(
                "panke:17378" to null,
                "panke:17224" to BigDecimal("15"),
                "panke:17227" to BigDecimal("12"),
                "panke:17337" to null,
                "panke:17348" to null,
                "panke:17361" to null,
                "panke:17314" to BigDecimal("15"),
                "panke:17363" to null,
                "panke:17365" to BigDecimal("15")
            )
        events.forEach { it.pricePresale.shouldBeNull() }
        events.filter { it.sourceId != "panke:17365" }.forEach { it.priceNote.shouldBeNull() }
    }

    @Test
    fun `stores no ticket link, the venue linking only Resident Advisor`() {
        events.forEach { it.ticketUrl.shouldBeNull() }
    }

    @Test
    fun `reads the poster out of the article's inline background image`() {
        // The template renders no <img> for an event at all, only this inline style.
        events.count { it.imageUrl != null } shouldBe 8
        parseBackgroundImageUrl("background-image: url(https://x.test/a.png); background-size: cover;") shouldBe
            "https://x.test/a.png"
        parseBackgroundImageUrl("background-size: cover;").shouldBeNull()
        parseBackgroundImageUrl(null).shouldBeNull()
    }

    @Test
    fun `stores no poster for the event whose background image is empty`() {
        // One article renders a literal `url()`, which must not become an image URL.
        event("panke:17363").imageUrl.shouldBeNull()
        parseBackgroundImageUrl("background-image: url(); background-size: cover;").shouldBeNull()
    }

    @Test
    fun `returns an empty list for a page without a programme`() {
        val document = Jsoup.parse("<html><body><div class='et_pb_events_0'></div></body></html>", sourceUrl)
        scraper.scrape(document, sourceUrl).shouldBeEmpty()
    }
}
