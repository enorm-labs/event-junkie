package de.norm.events.scraper.cosmiccomedy

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [CosmicComedyApiScraper].
 *
 * Parses static snapshots of the venue's The Events Calendar REST responses for deterministic,
 * offline-safe testing without HTTP fetching. Both pages of the programme are kept, so the cursor
 * and the tail of the listing are exercised on real data.
 */
class CosmicComedyApiScraperTest {
    private val scraper = CosmicComedyApiScraper()

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/cosmiccomedy/$name")!!
            .bufferedReader()
            .readText()

    private val pageOne: CosmicComedyPage by lazy { scraper.scrapePage(readFixture("cosmiccomedy-events-page1.json")) }
    private val pageTwo: CosmicComedyPage by lazy { scraper.scrapePage(readFixture("cosmiccomedy-events-page2.json")) }
    private val events: List<ScrapedEvent> by lazy { pageOne.events + pageTwo.events }

    /**
     * The two events that decide whether a `Comedy Special` names an act, captured verbatim from
     * the live endpoint: the house night filed as both a special and a showcase, and a real
     * comedian's special filed without one.
     */
    private val showcaseSpecial: List<ScrapedEvent> by lazy {
        scraper.scrapePage(readFixture("cosmiccomedy-events-showcase-special.json")).events
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `parses a full page and hands back the API's own cursor`() {
        pageOne.events shouldHaveSize 50
        pageOne.nextPageUrl!! shouldContain "page=2"
    }

    @Test
    fun `stops at the last page, which states no cursor`() {
        pageTwo.events shouldHaveSize 7
        pageTwo.nextPageUrl.shouldBeNull()
    }

    @Test
    fun `maps a fully populated event`() {
        val friday = event("cosmic_comedy:comedy-pizza-and-shots-showcase-friday-17")
        friday.title shouldBe "Comedy, Pizza and Shots – SHOWCASE FRIDAY"
        friday.eventType shouldBe EventType.SHOW.name
        friday.eventDate shouldBe LocalDate.of(2026, 8, 7)
        friday.startTime shouldBe LocalTime.of(19, 0)
        friday.sourceUrl shouldBe "https://comedyclubberlin.com/event/comedy-pizza-and-shots-showcase-friday-17/"
        friday.imageUrl!! shouldStartWith "https://comedyclubberlin.com/wp-content/uploads/"
        // This night names no organizer; the club sets one on roughly half its listings.
        friday.promoters.shouldBeEmpty()
    }

    @Test
    fun `decodes the HTML entities WordPress leaves in a title`() {
        // The API states "TURBOPAOLO &#8211; IL POLIZIOTTO…" and "NEW YEAR&#8217;S EVE SPECIAL".
        event("cosmic_comedy:turbopaolo-il-poliziotto-del-formaggio-2026").title shouldBe
            "TURBOPAOLO – IL POLIZIOTTO DEL FORMAGGIO 2026"
        events.none { it.title.contains("&#") } shouldBe true
    }

    @Test
    fun `flattens the description without the embedded ticket-widget script`() {
        val friday = event("cosmic_comedy:comedy-pizza-and-shots-showcase-friday-17")
        friday.description!! shouldContain "This show is in English!"
        // The field opens with a <script> whose body must not land in the description.
        events.none { it.description?.contains("universe.com/embed2.js") == true } shouldBe true
        events.none { it.description?.contains("data-widget-type") == true } shouldBe true
    }

    @Test
    fun `types every event as a show, the club programming nothing but comedy`() {
        events.all { it.eventType == EventType.SHOW.name } shouldBe true
        events shouldHaveSize 57
    }

    @Test
    fun `stores no genre, the categories naming a format or a language`() {
        // "Showcase", "Open Mic", "Comedy Special", "English Language" — none is a musical genre.
        events.none { it.genre != null } shouldBe true
    }

    @Test
    fun `names the performer only on a Comedy Special`() {
        // The club's own marker for a named act; those titles are all "<Performer> – <Show>".
        events.filter { it.artists.isNotEmpty() }.map { it.artists.single().name } shouldBe
            listOf("TURBOPAOLO", "Lucas Lauriente", "Mike Rice", "Chris Andrade", "Dan Docimo", "Tereza Hossa")
    }

    @Test
    fun `leaves the recurring house nights without an artist`() {
        // "Comedy, Pizza and Shots – SHOWCASE FRIDAY" names a series, not an act.
        event("cosmic_comedy:comedy-pizza-and-shots-showcase-friday-17").artists.shouldBeEmpty()
        event("cosmic_comedy:comedy-pizza-and-shots-open-mic-thursday-6").artists.shouldBeEmpty()
    }

    @Test
    fun `leaves a special that is also a showcase without an artist`() {
        // The club filed one edition of its house night as a Comedy Special as well, and the part
        // before the dash was stored as the act "Laughs, Pizza & Shots" (#1789).
        val showcase = showcaseSpecial.first { it.sourceId == "cosmic_comedy:65129" }
        showcase.title shouldBe "Laughs, Pizza & Shots – English Comedy Night in the Heart of Berlin!"
        showcase.artists.shouldBeEmpty()
    }

    @Test
    fun `still names the performer on a special that is not a showcase`() {
        // The control from the same payload: the pair of categories decides, not the Special alone.
        val special = showcaseSpecial.first { it.sourceId == "cosmic_comedy:turbopaolo-il-poliziotto-del-formaggio-2026" }
        special.artists.single().name shouldBe "TURBOPAOLO"
    }

    @Test
    fun `takes the ticket link from the event's website before the embedded widget`() {
        // One event links its promoter's own shop; the rest fall back to the Universe listing.
        event("cosmic_comedy:turbopaolo-il-poliziotto-del-formaggio-2026").ticketUrl shouldBe
            "https://trinitymusic.de/event/2026-09-28-turbopaolo"
        event("cosmic_comedy:comedy-pizza-and-shots-showcase-friday-17").ticketUrl!! shouldStartWith
            "https://www.universe.com/events/"
    }

    @Test
    fun `stores the organizer as the promoter, and none where the API states none`() {
        event("cosmic_comedy:turbopaolo-il-poliziotto-del-formaggio-2026").promoters shouldBe listOf("Trinity Music")
        // The club sets itself as organizer on its own nights, and leaves the field empty on 28.
        event("cosmic_comedy:comedy-pizza-and-shots-open-mic-thursday-6").promoters shouldBe listOf("Cosmic Comedy Berlin")
        events.count { it.promoters.isEmpty() } shouldBe 28
    }

    @Test
    fun `publishes no price on any event`() {
        events.forEach {
            it.pricePresale.shouldBeNull()
            it.priceBoxOffice.shouldBeNull()
            it.priceNote.shouldBeNull()
            it.free shouldBe false
        }
    }

    @Test
    fun `resolves every event to a date, a start time and a unique id`() {
        events.none { it.eventDate == UNRESOLVED_EVENT_DATE } shouldBe true
        events.none { it.startTime == null } shouldBe true
        // 57 dates resolve to only 11 distinct titles, so the slug is what identifies an event.
        events.map { it.sourceId }.toSet() shouldHaveSize 57
        events.map { it.title }.toSet() shouldHaveSize 11
    }

    @Test
    fun `degrades to an empty page for a body it cannot parse`() {
        val broken = scraper.scrapePage("not json at all")
        broken.events.shouldBeEmpty()
        broken.nextPageUrl.shouldBeNull()
    }

    @Test
    fun `degrades to an empty page for a body with no events array`() {
        val empty = scraper.scrapePage("""{"total":0,"total_pages":0}""")
        empty.events.shouldBeEmpty()
        empty.nextPageUrl.shouldBeNull()
    }
}
