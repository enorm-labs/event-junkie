package de.norm.events.scraper.aeg

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * Unit tests for [AegDetailPageScraper], the detail parser shared by the two Berlin AEG venues.
 *
 * Parses static snapshots of both venues' `/events/detail/<slug>/<YYYY-MM-DD-HHMM>` pages for
 * deterministic, offline-safe testing without HTTP fetching.
 */
class AegDetailPageScraperTest {
    private val scraper = AegDetailPageScraper()
    private val arenaUrl = "https://www.uber-arena.de/events/detail/diljit-dosanjh/2026-08-21-2000"
    private val musicHallUrl = "https://www.uber-eats-music-hall.de/events/detail/jony/2026-09-15-1900"

    private val arenaEvent: ScrapedEvent by lazy {
        scrape("uberarena-detail-diljit-dosanjh.html", arenaUrl, EventSource.UBER_ARENA)
    }
    private val musicHallEvent: ScrapedEvent by lazy {
        scrape("ubereatsmusichall-detail-jony.html", musicHallUrl, EventSource.UBER_EATS_MUSIC_HALL)
    }

    private fun scrape(
        fixture: String,
        sourceUrl: String,
        eventSource: EventSource
    ): ScrapedEvent {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/aeg/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl, eventSource)!!
    }

    @Test
    fun `reads the doors time, the only place the venue states one`() {
        arenaEvent.doorsTime shouldBe LocalTime.of(18, 30)
        // The music hall renders the same line without the arena's heading wrapper.
        musicHallEvent.doorsTime shouldBe LocalTime.of(17, 30)
    }

    @Test
    fun `reads the prose description`() {
        arenaEvent.description!! shouldContain "Punjabi"
        musicHallEvent.description!! shouldContain "Aserbaidschan"
    }

    @Test
    fun `reads the external ticket-shop link`() {
        arenaEvent.ticketUrl!! shouldStartWith "https://queue-de.axs.com/"
        musicHallEvent.ticketUrl!! shouldStartWith "https://queue-de.axs.com/"
    }

    @Test
    fun `skips the venue's own links in the ticket block`() {
        // The music hall renders a self-link before the shop link, with identical classes, and
        // follows the block with links to other events. Only an off-host link is a ticket link.
        musicHallEvent.ticketUrl shouldBe "https://queue-de.axs.com/?c=axsde&e=4231211618132917"
    }

    @Test
    fun `derives the sourceId from the full slug including its date segment`() {
        arenaEvent.sourceId shouldBe "uber_arena:diljit-dosanjh/2026-08-21-2000"
        musicHallEvent.sourceId shouldBe "uber_eats_music_hall:jony/2026-09-15-1900"
    }

    @Test
    fun `leaves the date to the listing`() {
        // The detail page renders no date this parser reads; the listing assembled it already.
        arenaEvent.eventDate shouldBe UNRESOLVED_EVENT_DATE
        musicHallEvent.eventDate shouldBe UNRESOLVED_EVENT_DATE
    }

    @Test
    fun `keeps the venue-suffixed heading for the importer to replace`() {
        // "Diljit Dosanjh in der Uber Arena" / "JONY live in der Uber Eats Music Hall" — the
        // listing's cleaner act name wins at the merge.
        arenaEvent.title shouldContain "in der Uber Arena"
        musicHallEvent.title shouldContain "in der Uber Eats Music Hall"
        arenaEvent.artists.shouldBeEmpty()
        musicHallEvent.artists.shouldBeEmpty()
    }

    @Test
    fun `bills a Line Up list of one act per line, in page order`() {
        val event =
            scrape(
                "uberarena-detail-90s-super-show.html",
                "https://www.uber-arena.de/events/detail/90s-super-show-2027/2027-02-20-1700",
                EventSource.UBER_ARENA
            )
        event.artists.map { it.name } shouldBe
            listOf(
                "Eiffel 65",
                "Haddaway",
                "Whigfield",
                "East 17",
                "Lou Bega",
                "Oli P.",
                "Masterboy & Beatrix Delgado",
                "Fun Factory",
                "Bellini",
                "Charlie Lownoise",
                "DJ Tomekk"
            )
        event.artists.map { it.role }.toSet() shouldBe setOf("HEADLINER")
    }

    @Test
    fun `bills a Line Up list that shares one line, split on its commas`() {
        val event =
            scrape(
                "uberarena-detail-beste-comedians.html",
                "https://www.uber-arena.de/events/detail/beste-comedians-26/2026-12-01-2000",
                EventSource.UBER_ARENA
            )
        event.artists.map { it.name } shouldBe
            listOf("Markus Krebs", "Nico Stank", "Osana Yaran", "Cindy aus Marzahn", "Atze Schröder", "Kristina Bogansky", "Tahnee")
    }

    @Test
    fun `bills nothing for a Line Up link without the label's colon`() {
        // Die Schlagernacht des Jahres links its bill: "…finden Sie hier: <a>Line Up</a>".
        val event =
            scrape(
                "uberarena-detail-schlagernacht.html",
                "https://www.uber-arena.de/events/detail/schlagernacht-2026/2026-11-21-1800",
                EventSource.UBER_ARENA
            )
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `reads the Line Up list to the end of its paragraph only`() {
        val html =
            """
            <h1 class="summary">Show in der Uber Arena</h1>
            <div class="event_body">
              <p>Intro.<br>Line-up: A, B<br>C &amp; D</p>
              <p>Tickets gibt es ab sofort.</p>
            </div>
            """.trimIndent()
        val event = scraper.scrape(Jsoup.parse(html, arenaUrl), arenaUrl, EventSource.UBER_ARENA)!!
        // Two lines, so each is one act, the comma included.
        event.artists.map { it.name } shouldBe listOf("A, B", "C & D")
    }

    @Test
    fun `states no price of its own`() {
        arenaEvent.pricePresale.shouldBeNull()
        arenaEvent.startTime.shouldBeNull()
        musicHallEvent.pricePresale.shouldBeNull()
        musicHallEvent.startTime.shouldBeNull()
    }

    @Test
    fun `returns null for a page without an event heading`() {
        val document = Jsoup.parse("<html><body><div id='content'></div></body></html>", arenaUrl)
        scraper.scrape(document, arenaUrl, EventSource.UBER_ARENA).shouldBeNull()
    }
}
