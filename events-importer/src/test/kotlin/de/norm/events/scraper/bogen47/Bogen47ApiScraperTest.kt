package de.norm.events.scraper.bogen47

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for [Bogen47ApiScraper], parsing the saved WordPress REST fixtures of LARK and Fitzroy.
 *
 * The scraper drops past-dated events, so every test runs against a fixed clock pinned to the day
 * the fixture was captured (2026-08-01).
 */
class Bogen47ApiScraperTest {
    private val clock: Clock = Clock.fixed(LocalDate.of(2026, 8, 1).atStartOfDay(BERLIN).toInstant(), BERLIN)
    private val scraper = Bogen47ApiScraper(EventSource.LARK, "https://larkberlin.com/events/", LARK_EVENT_TYPES, clock)

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/bogen47/$name")!!
            .bufferedReader()
            .readText()

    private val page: Bogen47Page by lazy { scraper.scrapePage(fixture("lark-events.json")) }
    private val events: List<ScrapedEvent> by lazy { page.entries.map { it.event } }

    private fun eventTitled(title: String): ScrapedEvent = events.first { it.title == title }

    /** One `Live` post with [title] as the API renders it, entities included. */
    private fun liveEventTitled(title: String): ScrapedEvent =
        scraper
            .scrapePage(
                """
                [{"id":9,"link":"https://larkberlin.com/event/x/","date":"2026-09-27T19:00:00",
                  "title":{"rendered":"$title"},"acf":{"event_type":"Live"},"featured_media":0}]
                """.trimIndent()
            ).entries
            .single()
            .event

    @Test
    fun `scrapePage keeps only upcoming events and reports the page's shape`() {
        // The captured page holds 100 posts spanning 2026-02-19 … 2027-02-27; 20 are upcoming.
        page.postCount shouldBe 100
        page.oldestDate shouldBe LocalDate.of(2026, 2, 19)
        events shouldHaveSize 20
        events.none { it.eventDate < LocalDate.of(2026, 8, 1) } shouldBe true
    }

    @Test
    fun `scrapePage extracts every field of a fully populated concert`() {
        val event = eventTitled("Ben Morgan")

        event.eventDate shouldBe LocalDate.of(2026, 10, 8)
        // The venue renders this time as "Doors"; it publishes no separate start time.
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime.shouldBeNull()
        event.eventType shouldBe EventType.CONCERT.name
        event.sourceId shouldBe "lark:7183"
        event.sourceUrl shouldBe "https://larkberlin.com/event/ben-morgan/"
        event.ticketUrl.shouldNotBeNull() shouldContain "schoneberg.de"
        event.description.shouldNotBeNull() shouldContain "Ben Morgan"
        event.status shouldBe EventStatus.SCHEDULED.name
        event.soldOut shouldBe false
        event.artists.map { it.name } shouldContainExactly listOf("Ben Morgan")
    }

    // Audit T-8: acf.event_description is markup, not text — it arrives with <p class="p1">
    // wrappers and <a href> links, and one event's whole description is a single anchor tag.
    // Stored raw, those tags reached the frontend verbatim.
    @Test
    fun `scrapePage renders the HTML description down to plain text`() {
        val descriptions = events.mapNotNull { it.description }

        descriptions.shouldNotBeEmpty()
        descriptions.forEach { description ->
            description shouldNotContain "<p"
            description shouldNotContain "<a "
            description shouldNotContain "</a>"
            description shouldNotContain "href="
            description shouldNotContain "&amp;"
            description shouldNotContain "&#8211;"
        }
        // The visible text survives the strip.
        eventTitled("Ben Morgan").description.shouldNotBeNull() shouldContain "Ben Morgan"
    }

    @Test
    fun `scrapePage reads the doors time from the post date, not the stale ACF field`() {
        // acf.event_doors_time is 19:00 on every post; this show's real doors time is 18:30, and
        // trusting the ACF field would put doors after the start.
        eventTitled("Kids with Buns").doorsTime shouldBe LocalTime.of(18, 30)
        eventTitled("FEUCHT").doorsTime shouldBe LocalTime.of(23, 59)
    }

    @Test
    fun `scrapePage reads a sold-out marker out of the title and strips it`() {
        val event = eventTitled("Flower Face")

        event.soldOut shouldBe true
        // acf.event_status reads "Scheduled" on every post, so sold-out is not a status here.
        event.status shouldBe EventStatus.SCHEDULED.name
        event.artists.map { it.name } shouldContainExactly listOf("Flower Face")
    }

    @Test
    fun `scrapePage bills a title's support act as SUPPORT`() {
        val event = eventTitled("Stu Larsen + Tim Hart (support)")

        event.artists.map { it.name } shouldContainExactly listOf("Stu Larsen", "Tim Hart")
        event.artists.map { it.role } shouldContainExactly listOf(ArtistRole.HEADLINER.name, ArtistRole.SUPPORT.name)
    }

    @Test
    fun `scrapePage bills the acts of a DJ night, not its series, epithet or joiners`() {
        // 27 September 2026, as the API renders the title.
        val event = liveEventTitled("Hum w/ Kyle Hall b2b K15, Mamalia&#8217;The first lady of modern funk&#8217;ft.Mauricio Fleury, Bulma Brief")

        event.artists.map { it.name } shouldContainExactly listOf("Kyle Hall", "K15", "Mamalia", "Mauricio Fleury", "Bulma Brief")
        event.artists.map { it.role }.toSet() shouldBe setOf(ArtistRole.HEADLINER.name)
    }

    @Test
    fun `scrapePage keeps a support act after a tour tail`() {
        val event = liveEventTitled("Greg Mendez &#8211; BEAUTY LAND TOUR +  Lily Seabird (support)")

        event.artists.map { it.name to it.role } shouldContainExactly
            listOf("Greg Mendez" to ArtistRole.HEADLINER.name, "Lily Seabird" to ArtistRole.SUPPORT.name)
    }

    @Test
    fun `scrapePage strips an en-dash tour tail from the derived act`() {
        // The venue writes tour tails with an en dash, which the shared ASCII-hyphen stripper misses.
        eventTitled("Greg Mendez – BEAUTY LAND TOUR").artists.map { it.name } shouldContainExactly listOf("Greg Mendez")
        eventTitled("Connor Kelly & The Time Warp – Europe 2026").artists.map { it.name } shouldContainExactly
            listOf("Connor Kelly & The Time Warp")
    }

    @Test
    fun `scrapePage classifies the act, not the tour name it carries`() {
        // The shared keyword classifier matches a bare "club", so this gig came back PARTY — and a
        // PARTY title is an event name, so it also lost its headliner.
        val event = eventTitled("LEILA – 20 SOMETHING CLUB TOUR")

        event.eventType shouldBe EventType.CONCERT.name
        event.artists.map { it.name } shouldContainExactly listOf("LEILA")
    }

    @Test
    fun `scrapePage stores no genre or prices as the venue's fields are unused defaults`() {
        val event = eventTitled("Maria Taylor")

        event.genre.shouldBeNull()
        event.pricePresale.shouldBeNull()
        event.priceBoxOffice.shouldBeNull()
        event.priceNote.shouldBeNull()
        event.free shouldBe false
    }

    @Test
    fun `scrapePage carries the featured media id for later poster resolution`() {
        val entry = page.entries.first { it.event.title == "Ben Morgan" }

        entry.featuredMediaId shouldBe 7184L
    }

    @Test
    fun `parseMedia maps attachment ids onto image URLs`() {
        val posters = scraper.parseMedia(fixture("lark-media.json"))

        posters.size shouldBe 20
        posters[7376L].shouldNotBeNull() shouldContain "larkberlin.com/wp-content/uploads/"
    }

    @Test
    fun `scrapePage types a party title without minting it as an artist`() {
        val json =
            """
            [{"id":1,"link":"https://larkberlin.com/event/x/","date":"2026-09-05T23:00:00",
              "title":{"rendered":"Basement Party"},"acf":{"event_type":"Club"},"featured_media":0}]
            """.trimIndent()

        val parsed =
            scraper
                .scrapePage(json)
                .entries
                .single()
                .event

        parsed.eventType shouldBe EventType.PARTY.name
        parsed.artists shouldHaveSize 0
    }

    @Test
    fun `scrapePage reads a cancellation marker and its note out of the title`() {
        val json =
            """
            [{"id":2,"link":"https://larkberlin.com/event/y/","date":"2026-09-06T19:00:00",
              "title":{"rendered":"COTI &#8211; Live in Berlin CANCELLED (follow ticket link for refunds)"},
              "acf":{},"featured_media":0},
             {"id":3,"link":"https://larkberlin.com/event/z/","date":"2026-09-07T19:00:00",
              "title":{"rendered":"CANCELLED: Le Volume Courbe"},"acf":{},"featured_media":0}]
            """.trimIndent()

        val parsed = scraper.scrapePage(json).entries.map { it.event }

        parsed.map { it.title } shouldContainExactly listOf("COTI – Live in Berlin", "Le Volume Courbe")
        parsed.map { it.status }.toSet() shouldBe setOf(EventStatus.CANCELLED.name)
    }

    @Test
    fun `scrapePage reads every status word the shared title rule knows`() {
        liveEventTitled("Olga Myko fällt leider aus").status shouldBe EventStatus.CANCELLED.name
        liveEventTitled("ABSAGE: Le Volume Courbe").status shouldBe EventStatus.CANCELLED.name
        liveEventTitled("Le Volume Courbe - verschoben").status shouldBe EventStatus.POSTPONED.name
    }

    @Test
    fun `scrapePage records the promoter the venue names`() {
        val json =
            """
            [{"id":4,"link":"https://larkberlin.com/event/p/","date":"2026-09-08T19:00:00",
              "title":{"rendered":"Some Act"},"acf":{"event_organizer":"Trinity Music"},"featured_media":0}]
            """.trimIndent()

        scraper
            .scrapePage(json)
            .entries
            .single()
            .event.promoters shouldContainExactly listOf("Trinity Music")
    }

    @Test
    fun `scrapePage skips posts missing an id, a date or a title`() {
        val json =
            """
            [{"link":"https://larkberlin.com/event/a/","date":"2026-09-09T19:00:00","title":{"rendered":"No id"},"acf":{}},
             {"id":5,"date":"not-a-date","title":{"rendered":"Bad date"},"acf":{}},
             {"id":6,"date":"2026-09-09T19:00:00","title":{"rendered":"   "},"acf":{}},
             {"id":7,"link":"https://larkberlin.com/event/k/","date":"2026-09-09T19:00:00","title":{"rendered":"Keeper"},"acf":{}}]
            """.trimIndent()

        val parsed = scraper.scrapePage(json).entries.map { it.event }

        parsed.map { it.sourceId } shouldContainExactly listOf("lark:7")
        parsed.single().title shouldBe "Keeper"
    }

    @Test
    fun `scrapePage returns an empty page for an unparseable or non-array body`() {
        scraper.scrapePage("not json").entries shouldHaveSize 0
        scraper.scrapePage("""{"code":"rest_no_route"}""").entries shouldHaveSize 0
        scraper.scrapePage("not json").postCount shouldBe 0
    }

    @Test
    fun `parseMedia degrades to an empty map for a malformed body`() {
        scraper.parseMedia("not json").size shouldBe 0
        scraper.parseMedia("""{"code":"rest_forbidden"}""").size shouldBe 0
    }

    private val fitzroy: List<ScrapedEvent> by lazy {
        val fitzroyClock = Clock.fixed(LocalDate.of(2026, 10, 1).atStartOfDay(BERLIN).toInstant(), BERLIN)
        Bogen47ApiScraper(EventSource.FITZROY, "https://fitzroy-berlin.de/events/", emptyMap(), fitzroyClock)
            .scrapePage(fixture("fitzroy-events.json"))
            .entries
            .map { it.event }
    }

    @Test
    fun `parses Fitzroy's upcoming nights from the same theme`() {
        // 100 posts, newest first; seven are on or after the capture day.
        fitzroy shouldHaveSize 7
        fitzroy.map { it.eventType }.distinct() shouldContainExactly listOf(EventType.PARTY.name)

        val night = fitzroy.first { it.title == "SLANG with Shy One, IG Culture & NothingDoing" }
        night.eventDate shouldBe LocalDate.of(2026, 10, 30)
        night.doorsTime shouldBe LocalTime.of(22, 0)
        night.sourceId shouldBe "fitzroy:5060"
        night.sourceUrl shouldBe "https://fitzroy-berlin.de/event/slang-with-shy-one-ig-culture-nothingdoing/"
        night.ticketUrl shouldBe "https://fr.ra.co/events/2533576"
        night.description.shouldNotBeNull() shouldContain "London OG Shy One"
    }

    @Test
    fun `drops a description that only says TBA`() {
        fitzroy.first { it.title == "Essential Delights" }.description.shouldBeNull()
    }

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
