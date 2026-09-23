package de.norm.events.scraper.aeg

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [AegOverviewPageScraper], the listing parser shared by the two Berlin AEG venues.
 *
 * Parses static snapshots of both venues' `/events/all` pages for deterministic, offline-safe
 * testing without HTTP fetching. The arena fixture keeps **all 128 rows**, sport included, so the
 * category filter is exercised on real data; the music hall fixture keeps all 66 of its own.
 */
class AegOverviewPageScraperTest {
    private val scraper = AegOverviewPageScraper()
    private val arenaUrl = "https://www.uber-arena.de/events/all"
    private val musicHallUrl = "https://www.uber-eats-music-hall.de/events/all"

    private val arenaEvents: List<ScrapedEvent> by lazy {
        scrape("uberarena-overview.html", arenaUrl, EventSource.UBER_ARENA)
    }
    private val musicHallEvents: List<ScrapedEvent> by lazy {
        scrape("ubereatsmusichall-overview.html", musicHallUrl, EventSource.UBER_EATS_MUSIC_HALL)
    }

    private fun scrape(
        fixture: String,
        baseUrl: String,
        eventSource: EventSource
    ): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/aeg/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl, eventSource)
    }

    private fun arena(sourceId: String): ScrapedEvent = arenaEvents.first { it.sourceId == sourceId }

    private fun musicHall(sourceId: String): ScrapedEvent = musicHallEvents.first { it.sourceId == sourceId }

    @Test
    fun `drops the arena's sport fixtures and keeps everything else`() {
        // 128 rows in the fixture: 64 konzert, 15 show, 9 comedy — and 40 eishockey/basketball/sport.
        arenaEvents shouldHaveSize 88
        arenaEvents.none { it.title.contains("FIBA") } shouldBe true
    }

    @Test
    fun `keeps every music hall row, a venue with no resident team`() {
        musicHallEvents shouldHaveSize 66
    }

    @Test
    fun `maps a fully populated arena row`() {
        val diljit = arena("uber_arena:diljit-dosanjh/2026-08-21-2000")
        diljit.title shouldBe "Diljit Dosanjh"
        diljit.eventType shouldBe EventType.CONCERT.name
        diljit.eventDate shouldBe LocalDate.of(2026, 8, 21)
        diljit.startTime shouldBe LocalTime.of(20, 0)
        diljit.sourceUrl shouldBe "https://www.uber-arena.de/events/detail/diljit-dosanjh/2026-08-21-2000"
        diljit.pricePresale shouldBe BigDecimal("83.50")
        diljit.priceNote shouldBe "ab 83,50 €"
        diljit.imageUrl!! shouldStartWith "https://www.uber-arena.de/assets/img/"
        diljit.artists.map { it.name } shouldBe listOf("Diljit Dosanjh")
    }

    @Test
    fun `maps a fully populated music hall row`() {
        val jony = musicHall("uber_eats_music_hall:jony/2026-09-15-1900")
        jony.title shouldBe "JONY"
        jony.eventType shouldBe EventType.CONCERT.name
        jony.eventDate shouldBe LocalDate.of(2026, 9, 15)
        jony.startTime shouldBe LocalTime.of(19, 0)
        jony.sourceUrl shouldBe "https://www.uber-eats-music-hall.de/events/detail/jony/2026-09-15-1900"
        // Both venues' posters are served from the arena's bucket — one tenant, one asset host.
        jony.imageUrl!! shouldStartWith "https://uber-arena.production.carbonhouse.com/assets/img/"
        jony.artists.map { it.name } shouldBe listOf("JONY")
    }

    @Test
    fun `assembles the date from the venue's separate day month and year spans`() {
        // Each span carries its own punctuation: "21." / "08." / "2026,".
        arena("uber_arena:diljit-dosanjh/2026-08-21-2000").eventDate shouldBe LocalDate.of(2026, 8, 21)
    }

    @Test
    fun `reads the music hall's German month abbreviations where the arena writes a number`() {
        // The one tenant difference in the date group: "Sep. " / "Mär " / "Jun " vs "08.".
        musicHall("uber_eats_music_hall:sticks-and-stones-2027/2027-06-05-1000").eventDate shouldBe
            LocalDate.of(2027, 6, 5)
        musicHall("uber_eats_music_hall:jony/2026-09-15-1900").eventDate shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `types the arena's own category names`() {
        arenaEvents.map { it.eventType }.toSet() shouldBe
            setOf(EventType.CONCERT.name, EventType.SHOW.name)
        // 15 "show" + 9 "comedy" rows both resolve to SHOW; the rest are concerts.
        arenaEvents.count { it.eventType == EventType.SHOW.name } shouldBe 24
        arenaEvents.count { it.eventType == EventType.CONCERT.name } shouldBe 64
    }

    @Test
    fun `types the music hall's rows through the platform's numeric taxonomy`() {
        // This venue publishes no `data-categoryname`, only the id: 47x konzert, 14x show,
        // 4x comedy — and one row filed under no category at all.
        musicHallEvents.count { it.eventType == EventType.CONCERT.name } shouldBe 47
        musicHallEvents.count { it.eventType == EventType.SHOW.name } shouldBe 18
    }

    @Test
    fun `states no type for the row the venue filed under no category`() {
        // Category id 0: a job fair, which is neither a concert nor a staged show. Leaving the
        // type unstated is truthful — `toEventEntity` settles it as OTHER rather than a concert.
        val jobFair = musicHall("uber_eats_music_hall:sticks-and-stones-2027/2027-06-05-1000")
        jobFair.eventType.shouldBeNull()
        jobFair.title shouldBe "STICKS & STONES - LGBTIQ+ Job - und Karrieremesse"
        musicHallEvents.count { it.eventType == null } shouldBe 1
    }

    @Test
    fun `reads the cancellation prefix as a status and keeps it out of the title`() {
        // "ABGESAGT: Ryan Adams" — the only place either venue states a scheduling status.
        val cancelled = musicHallEvents.filter { it.status == EventStatus.CANCELLED.name }
        cancelled.map { it.title } shouldBe listOf("Arena Rave", "Ryan Adams")
        musicHallEvents.count { it.status == EventStatus.SCHEDULED.name } shouldBe 64
        arenaEvents.all { it.status == EventStatus.SCHEDULED.name } shouldBe true
    }

    // The venues name the change rather than the verb, so none of the move words reached it (#1771).
    @Test
    fun `reads a VENUE ÄNDERUNG prefix as a move and keeps it out of the title and the lineup`() {
        val row =
            """
            <html><body>
              <div class="active-date entry clearfix" data-category="3" data-categoryname="konzert" data-date="2026-09">
                <div class="info"><div class="details">
                  <div class="date"><span class="m-date__singleDate"><span class="m-date__day">23.</span><span
                      class="m-date__month">09.</span><span class="m-date__year">2026, </span><span
                      class="m-date__hour"> 20:00 Uhr</span></span></div>
                  <h3 class="event-title"><a
                      href="https://www.uber-arena.de/events/detail/jazeek-26/2026-09-23-2000">VENUE ÄNDERUNG: Jazeek</a></h3>
                </div></div>
              </div>
            </body></html>
            """.trimIndent()
        val event = scraper.scrape(Jsoup.parse(row, arenaUrl), arenaUrl, EventSource.UBER_ARENA).single()

        event.status shouldBe EventStatus.RELOCATED.name
        event.title shouldBe "Jazeek"
        // The notice is no act: it was minted as `venue-anderung-jazeek` beside the real Jazeek.
        event.artists.map { it.name } shouldBe listOf("Jazeek")
    }

    @Test
    fun `resolves the destination the event's own page names`() {
        // The relocation parsing already worked; a SCHEDULED status threw the answer away (#1771).
        val moved =
            ScrapedEvent(
                title = "Jazeek",
                eventDate = LocalDate.of(2026, 9, 23),
                sourceUrl = "https://www.uber-arena.de/events/detail/jazeek-26/2026-09-23-2000",
                sourceId = "uber_arena:jazeek-26/2026-09-23-2000",
                status = EventStatus.RELOCATED.name,
                description =
                    "Das Konzert von Jazeek, das am 23. September 2026 in der Uber Arena stattfinden sollte, " +
                        "wird in die Uber Eats Music Hall verlegt."
            )
        val entity = moved.toEventEntity(venueId = 1, venueSlug = "uber-arena", eventSourceId = 1)

        entity.status shouldBe EventStatus.RELOCATED.name
        entity.relocatedTo shouldBe "Uber Eats Music Hall"
    }

    @Test
    fun `keeps one production's many dates apart by the date segment in its url`() {
        // A run reuses a single slug, so only the trailing "/YYYY-MM-DD-HHMM" makes it unique.
        arenaEvents.map { it.sourceId }.toSet() shouldHaveSize arenaEvents.size
        // The music hall's Nutcracker runs eight times off one slug.
        musicHallEvents.count { it.sourceId.startsWith("uber_eats_music_hall:nussknacker-2026/") } shouldBe 8
        musicHallEvents.map { it.sourceId }.toSet() shouldHaveSize musicHallEvents.size
    }

    @Test
    fun `parses every kept row into a resolved date and start time`() {
        (arenaEvents + musicHallEvents).none { it.eventDate == UNRESOLVED_EVENT_DATE } shouldBe true
        (arenaEvents + musicHallEvents).none { it.startTime == null } shouldBe true
    }

    @Test
    fun `stores no price where the venue has not announced one`() {
        // The three arena "6K UNITED!" dates render a non-breaking space in place of the price,
        // which must not become a zero or an empty note.
        val priceless = arenaEvents.filter { it.pricePresale == null }
        priceless.map { it.title }.toSet() shouldBe setOf("6K UNITED!")
        priceless shouldHaveSize 3
        priceless.forEach { it.priceNote.shouldBeNull() }
        // The music hall simply omits the span on its two cancelled dates, the job fair and JONY.
        musicHallEvents.filter { it.pricePresale == null } shouldHaveSize 4
    }

    @Test
    fun `reads the music hall's from-price out of its nested markup`() {
        // The span wraps the "ab" in an <i>: <span class="price"><i class="ab">ab</i> 45,00 €</span>.
        val somuncu = musicHall("uber_eats_music_hall:serdar-somuncu/2026-10-11-1900")
        somuncu.pricePresale shouldBe BigDecimal("45.00")
        somuncu.priceNote shouldBe "ab 45,00 €"
    }

    @Test
    fun `leaves the detail-only fields empty`() {
        val diljit = arena("uber_arena:diljit-dosanjh/2026-08-21-2000")
        diljit.doorsTime.shouldBeNull()
        diljit.description.shouldBeNull()
        diljit.ticketUrl.shouldBeNull()
    }

    @Test
    fun `returns an empty list for a page without rows`() {
        val document = Jsoup.parse("<html><body><div id='content'></div></body></html>", arenaUrl)
        scraper.scrape(document, arenaUrl, EventSource.UBER_ARENA).shouldBeEmpty()
    }
}
