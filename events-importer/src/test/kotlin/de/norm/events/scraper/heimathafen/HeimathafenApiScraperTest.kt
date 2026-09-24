package de.norm.events.scraper.heimathafen

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for [HeimathafenApiScraper].
 *
 * Uses a real page of the venue's WP REST response, pinned to a fixed clock (the capture date) so
 * the past-performance cut-off is deterministic. The fixture is what proves the central behaviour:
 * the archive is dominated by past posts, one post expands into many dated performances, and the
 * venue's own taxonomy slug types the event. Statuses absent from the upcoming slice
 * (`ausverkauft`, `entfallt`, `verlegt`) are covered by small hand-built payloads.
 */
class HeimathafenApiScraperTest {
    /** Pinned to the fixture's capture date so "upcoming" is stable. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-01T09:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = HeimathafenApiScraper(clock)
    private lateinit var page: HeimathafenApiScraper.HeimathafenPage

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/heimathafen/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        page = scraper.scrape(fixture("heimathafen-events-page1.json"))
    }

    private fun event(sourceIdSuffix: String): ScrapedEvent = page.events.first { it.sourceId == "heimathafen:$sourceIdSuffix" }

    /** Wraps [performance] into a minimal one-post payload, so a status can be tested in isolation. */
    private fun payloadWith(performance: String): String =
        """
        [{"id":99,"link":"https://heimathafen-neukoelln.de/events/x/","title":{"rendered":"TEST"},
          "excerpt":{"rendered":""},"content":{"rendered":""},"class_list":["events_cat-musik"],
          "featured_images":{},"acf":{"event_performances":[$performance]}}]
        """.trimIndent()

    /** Wraps a title and an `event_cast` into a minimal one-post payload with one performance. */
    private fun payloadWithCast(
        title: String,
        cast: String
    ): String =
        """
        [{"id":98,"link":"https://heimathafen-neukoelln.de/events/y/","title":{"rendered":"$title"},
          "excerpt":{"rendered":""},"content":{"rendered":""},"class_list":["events_cat-musik"],
          "featured_images":{},"acf":{"event_cast":"$cast",
          "event_performances":[{"performance_date_time":"11/02/2026 8:00 p.m."}]}}]
        """.trimIndent()

    // The venue names each act as the lead <strong> of its own paragraph, which is what decides a
    // comma no rule over the title can safely cut (#1789).
    @Test
    fun `bills the acts the cast names when the title names them too`() {
        val cast =
            "<p><strong>Jamie Baum Quartet</strong><br />Jamie Baum: flutes</p>" +
                "<p><strong>Anke Helfrich Trio</strong><br />Anke Helfrich: piano</p>" +
                "<p><strong>Judith Owen Septett</strong><br />Judith Owen: vocals</p>"
        val events = scraper.scrape(payloadWithCast("JAMIE BAUM QUARTET, ANKE HELFRICH TRIO &amp; JUDITH OWEN SEPTETT", cast)).events

        events.single().artists.map { it.name } shouldContainExactly
            listOf("Jamie Baum Quartet", "Anke Helfrich Trio", "Judith Owen Septett")
    }

    // The same field carries a theatre bill's section labels, which name nobody the title bills.
    @Test
    fun `ignores a cast whose leads the title does not name`() {
        val cast =
            "<p><strong>Die Rixdorfer Perlen sind</strong><br />Mieze, Marianne, Jule</p>" +
                "<p><strong>In weiteren Rollen</strong><br />Inka Löwendorf</p>"
        val events = scraper.scrape(payloadWithCast("DIE RIXDORFER PERLEN", cast)).events

        events.single().artists.map { it.name } shouldContainExactly listOf("DIE RIXDORFER PERLEN")
    }

    // One lead corroborates nothing the title does not already say.
    @Test
    fun `ignores a cast naming a single act`() {
        val events = scraper.scrape(payloadWithCast("KAE TEMPEST", "<p><strong>Kae Tempest</strong><br />vocals</p>")).events

        events.single().artists.map { it.name } shouldContainExactly listOf("KAE TEMPEST")
    }

    @Test
    fun `reports the page's post count so the caller can page`() {
        page.postCount shouldBe 100
    }

    @Test
    fun `keeps only upcoming performances out of the archive page`() {
        // The page holds 100 posts spanning years of archive; only these performances are still to come.
        page.events shouldHaveSize 67
        page.events.none { it.eventDate.isBefore(LocalDate.of(2026, 8, 1)) } shouldBe true
    }

    @Test
    fun `parses every field of a performance`() {
        val seyda = event("30756-2026-09-02-1930")
        seyda.title shouldBe "ŞEYDA KURT & MARIA POPOV"
        seyda.subtitle shouldBe "»Zeit der Monster« Buchpremiere"
        // events_cat-literatur → a reading, not a concert.
        seyda.eventType shouldBe "READING"
        seyda.eventDate shouldBe LocalDate.of(2026, 9, 2)
        seyda.startTime shouldBe LocalTime.of(19, 30)
        // "Einlass ca. 18:30 Uhr" — the venue writes the qualifier three different ways.
        seyda.doorsTime shouldBe LocalTime.of(18, 30)
        seyda.status shouldBe "SCHEDULED"
        seyda.soldOut shouldBe false
        seyda.sourceUrl shouldBe "https://heimathafen-neukoelln.de/events/seyda-kurt-und-maria-popov/"
        seyda.ticketUrl.shouldNotBeNull() shouldStartWith "https://heimathafen-neukoelln.reservix.de/"
        seyda.imageUrl.shouldNotBeNull() shouldStartWith "https://heimathafen-neukoelln.de/wp-content/uploads/"
        seyda.description.shouldNotBeNull()
        seyda.pricePresale shouldBe BigDecimal("18.00")
    }

    @Test
    fun `keeps every price tier in the note, not just the two mapped columns`() {
        val seyda = event("30756-2026-09-02-1930")
        seyda.priceNote.shouldNotBeNull() shouldContain "ZUGABE TICKET"
        seyda.priceNote.shouldNotBeNull() shouldContain "Tickets: 18,00 €"
    }

    @Test
    fun `never reads a social concession as the box-office price`() {
        // DIE KLIMA-MONOLOGE prices five tiers: Regulär 20,00 €, Ermäßigt 14,50 €, "Mit Berlin-Pass
        // (Abendkasse)" 3,00 €, "Für Geflüchtete (Abendkasse)" 0,00 €, ZUGABE TICKET 25,00 €.
        // Matching "Abendkasse" anywhere in the label would store €3 as the door price — and the €0
        // tier would mark the whole event free.
        val json =
            """
            [{"id":9,"link":"https://heimathafen-neukoelln.de/events/k/","title":{"rendered":"DIE KLIMA-MONOLOGE"},
              "excerpt":{"rendered":""},"content":{"rendered":""},"class_list":["events_cat-theater"],"featured_images":{},
              "acf":{"event_prices":[
                 {"event_prices_label":"Regulär","event_prices_price":"20,00 €"},
                 {"event_prices_label":"Ermäßigt","event_prices_price":"14,50 €"},
                 {"event_prices_label":"Mit Berlin-Pass (Abendkasse)","event_prices_price":"3,00 €"},
                 {"event_prices_label":"Für Geflüchtete (Abendkasse)","event_prices_price":"0,00 €"},
                 {"event_prices_label":"ZUGABE TICKET (unser Support Ticket)","event_prices_price":"25,00 €"}],
                 "event_performances":[{"performance_date_time":"11/27/2026 8:00 p.m.","performance_status":"default"}]}}]
            """.trimIndent()
        val event = scraper.scrape(json).events.single()
        event.pricePresale shouldBe BigDecimal("20.00")
        event.priceBoxOffice.shouldBeNull()
        event.free shouldBe false
        // Every tier is still recoverable from the note.
        event.priceNote.shouldNotBeNull() shouldContain "Mit Berlin-Pass (Abendkasse): 3,00 €"
    }

    @Test
    fun `reads a general Abendkasse label as the box-office price`() {
        val json =
            """
            [{"id":10,"link":"https://heimathafen-neukoelln.de/events/a/","title":{"rendered":"BAND"},
              "excerpt":{"rendered":""},"content":{"rendered":""},"class_list":["events_cat-musik"],"featured_images":{},
              "acf":{"event_prices":[
                 {"event_prices_label":"Vorverkauf","event_prices_price":"18,00 €"},
                 {"event_prices_label":"Abendkasse","event_prices_price":"22,00 €"}],
                 "event_performances":[{"performance_date_time":"11/27/2026 8:00 p.m.","performance_status":"default"}]}}]
            """.trimIndent()
        val event = scraper.scrape(json).events.single()
        event.pricePresale shouldBe BigDecimal("18.00")
        event.priceBoxOffice shouldBe BigDecimal("22.00")
    }

    @Test
    fun `expands one post's run into a dated event per performance`() {
        val run = page.events.filter { it.sourceId.startsWith("heimathafen:30674-") }
        run shouldHaveSize 9
        run.map { it.sourceId }.distinct() shouldHaveSize 9
        run.map { it.title }.distinct() shouldHaveSize 1
    }

    @Test
    fun `keeps two performances on the same day apart by their start time`() {
        // A run that plays twice on one date would collapse onto one row under a date-only key.
        val sameDay = page.events.groupBy { it.sourceId.substringBeforeLast('-') }.filterValues { it.size > 1 }
        sameDay.values
            .flatten()
            .map { it.sourceId }
            .distinct() shouldHaveSize sameDay.values.sumOf { it.size }
    }

    @Test
    fun `types a music post as a concert and derives its headliner`() {
        val concerts = page.events.filter { it.eventType == "CONCERT" }
        concerts.isNotEmpty() shouldBe true
        concerts.all { it.artists.isNotEmpty() } shouldBe true
    }

    @Test
    fun `types a theatre post as a show and derives no artists from its title`() {
        val show = event("30152-2026-09-03-1900")
        show.eventType shouldBe "SHOW"
        show.doorsTime shouldBe LocalTime.of(18, 45)
        show.artists.shouldBeEmpty()
    }

    @Test
    fun `flags a free-entry performance from its status`() {
        val gossip = event("30866-2026-09-15-1830")
        gossip.free shouldBe true
        gossip.status shouldBe "SCHEDULED"
    }

    @Test
    fun `reads a promoter only from the unambiguous organiser phrasing`() {
        // "Eine Veranstaltung des Heimathafen Neukölln in Kooperation mit BUCHBOX!" credits the
        // venue itself, so no promoter is minted from it.
        event("30756-2026-09-02-1930").promoters.shouldBeEmpty()
        page.events.flatMap { it.promoters }.none { it.contains("Kooperation") } shouldBe true
    }

    @Test
    fun `maps sold-out, cancelled and relocated statuses`() {
        val soldOut = scraper.scrape(payloadWith("""{"performance_date_time":"11/27/2026 8:00 p.m.","performance_status":"ausverkauft"}""")).events.single()
        soldOut.soldOut shouldBe true
        soldOut.status shouldBe "SCHEDULED"

        val cancelled = scraper.scrape(payloadWith("""{"performance_date_time":"11/27/2026 8:00 p.m.","performance_status":"entfallt"}""")).events.single()
        cancelled.status shouldBe "CANCELLED"

        val relocated = scraper.scrape(payloadWith("""{"performance_date_time":"11/27/2026 8:00 p.m.","performance_status":"verlegt"}""")).events.single()
        relocated.status shouldBe "RELOCATED"
    }

    @Test
    fun `reads the promoter from an Eine Veranstaltung von credit`() {
        val json =
            """
            [{"id":7,"link":"https://heimathafen-neukoelln.de/events/y/","title":{"rendered":"BAND"},
              "excerpt":{"rendered":""},"content":{"rendered":""},"class_list":["events_cat-musik"],
              "featured_images":{},
              "acf":{"event_organiser":"<p>Eine Veranstaltung von <a href=\"https://berlinkonzerte.de\"><strong>New Berlin Konzerte</strong></a></p>",
                     "event_performances":[{"performance_date_time":"11/27/2026 8:00 p.m.","performance_status":"default"}]}}]
            """.trimIndent()
        scraper
            .scrape(json)
            .events
            .single()
            .promoters shouldContainExactly listOf("New Berlin Konzerte")
    }

    @Test
    fun `derives the headliner from a concert title`() {
        val json =
            """
            [{"id":8,"link":"https://heimathafen-neukoelln.de/events/z/","title":{"rendered":"DRANGSAL"},
              "excerpt":{"rendered":""},"content":{"rendered":""},"class_list":["events_cat-musik"],
              "featured_images":{},"acf":{"event_performances":[{"performance_date_time":"11/27/2026 8:00 p.m.","performance_status":"default"}]}}]
            """.trimIndent()
        scraper
            .scrape(json)
            .events
            .single()
            .artists shouldContainExactly listOf(ScrapedArtist("DRANGSAL", "HEADLINER", titleDerived = true))
    }

    @Test
    fun `skips a performance whose timestamp is unparseable`() {
        scraper.scrape(payloadWith("""{"performance_date_time":"soon","performance_status":"default"}""")).events.shouldBeEmpty()
    }

    @Test
    fun `returns an empty page for a payload that is not an array`() {
        val empty = scraper.scrape("""{"code":"rest_post_invalid_page_number"}""")
        empty.postCount shouldBe 0
        empty.events.shouldBeEmpty()
    }

    @Test
    fun `returns an empty page for unparseable JSON`() {
        scraper.scrape("not json").events.shouldBeEmpty()
    }

    @Test
    fun `reads the genres among the tag slugs and leaves the formats and rooms out`() {
        // `events_tag-rb, -konzert, -soul, -blues`: the lossy `rb` is R&B by hand, `konzert` is a format (#313).
        page.events.first { it.title.startsWith("EB DAVIS") }.genre shouldBe "R&B, soul, blues"
        // `-konzert, -pop, -deutschrap, -rap-pop`
        page.events.first { it.title == "BAC" }.genre shouldBe "pop, deutschrap, rap pop"
        // `-comedy` alone is a format, not a genre.
        page.events.first { it.title == "PATRICK SPICER" }.genre shouldBe null
    }

    @Test
    fun `parses the short last page of the archive`() {
        val last = scraper.scrape(fixture("heimathafen-events-page5.json"))
        last.postCount shouldBe 8
        // A short page is the caller's signal to stop paging; it still carries upcoming events.
        last.events.isNotEmpty() shouldBe true
    }
}
