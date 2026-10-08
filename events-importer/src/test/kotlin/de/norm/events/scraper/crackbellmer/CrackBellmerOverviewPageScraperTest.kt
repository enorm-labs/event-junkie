package de.norm.events.scraper.crackbellmer

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for [CrackBellmerOverviewPageScraper].
 *
 * Uses a real programme snapshot under two fixed clocks. The listing carries about a month of
 * already-passed nights, so the capture-date clock exercises the cutoff while an earlier one keeps
 * the whole snapshot visible — which is the only way to assert against the July nights, where most
 * of the venue's odd lineup spellings sit.
 */
class CrackBellmerOverviewPageScraperTest {
    /** The fixture's capture date, so the past-event cutoff is stable. */
    private val captureDate: Clock = Clock.fixed(Instant.parse("2026-08-04T10:00:00Z"), BERLIN)

    /** Before the earliest night in the fixture, so nothing is dropped as past. */
    private val beforeProgramme: Clock = Clock.fixed(Instant.parse("2026-07-01T10:00:00Z"), BERLIN)

    private val sourceUrl = "https://www.crackbellmer.de/program/this-month"
    private val upcoming = scrape(captureDate)
    private val everything = scrape(beforeProgramme)

    private fun scrape(clock: Clock): List<ScrapedEvent> = CrackBellmerOverviewPageScraper(clock).scrape(Jsoup.parse(fixture(), sourceUrl), sourceUrl)

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/crackbellmer/crackbellmer-overview.html")!!
            .bufferedReader()
            .readText()

    private fun List<ScrapedEvent>.on(
        date: LocalDate,
        title: String
    ): ScrapedEvent = first { it.eventDate == date && it.title == title }

    @Test
    fun `keeps only the upcoming nights of the listing`() {
        // The three month tabs serve one list holding the whole programme, past nights included.
        upcoming shouldHaveSize 31
        upcoming.first().eventDate shouldBe LocalDate.of(2026, 8, 4)
        upcoming.last().eventDate shouldBe LocalDate.of(2026, 8, 30)
    }

    @Test
    fun `parses a fully populated night`() {
        val night = everything.on(LocalDate.of(2026, 8, 7), "BLURRY VISION")
        night.startTime shouldBe LocalTime.of(22, 0)
        night.eventType shouldBe "PARTY"
        night.genre shouldBe "House, Techno"
        night.sourceUrl shouldBe "https://www.crackbellmer.de/events/blurry-vision-761be"
        night.sourceId shouldBe "crack_bellmer:blurry-vision-761be"
        night.imageUrl shouldBe
            "https://cdn.prod.website-files.com/6881e4d575c26002129503dc/" +
            "6a67568636e04c52937da081_a20146767d979d0e4ed096b1d1f8609c538a4802.webp"
        night.artists.map { it.name } shouldContainExactly listOf("Hitomi (An toi)", "MLE", "Selessa T", "Viénce", "Otal")
        // The venue publishes none of these anywhere on the site.
        night.doorsTime.shouldBeNull()
        night.ticketUrl.shouldBeNull()
        night.pricePresale.shouldBeNull()
    }

    @Test
    fun `reads the year from the data-date attribute`() {
        // The rendered calendar column spells this night "Sun . 30 . 8 ." — day and month only.
        everything.on(LocalDate.of(2026, 8, 30), "DYKE GOTH NIGHT").sourceId shouldBe "crack_bellmer:dyke-goth-night-b47f9"
    }

    @Test
    fun `nulls the Webflow placeholder poster`() {
        everything.on(LocalDate.of(2026, 8, 6), "BELLMER BAR").imageUrl.shouldBeNull()
    }

    @Test
    fun `skips the venue's closed-day entries`() {
        // 9 July is a "CLOSED" entry in the listing; the other 66 items are real nights.
        everything shouldHaveSize 66
        everything.map { it.title } shouldNotContain "CLOSED"
    }

    @Test
    fun `types a night from its genre line when the title carries no cue`() {
        everything.on(LocalDate.of(2026, 7, 24), "CHANDELIER: KRUSIMUSI").let {
            it.genre shouldBe "Concert meets Pub Quiz"
            it.eventType shouldBe "QUIZ"
        }
    }

    @Test
    fun `defaults a cue-less night to a party`() {
        everything.on(LocalDate.of(2026, 8, 13), "ỌGBỌ × SIMBIOSIS").eventType shouldBe "PARTY"
    }

    @Test
    fun `splits a comma-separated billing and marks the live act`() {
        val night = everything.on(LocalDate.of(2026, 8, 4), "BELLMER BALBOA")
        // "Practically Married Quartet (live)" — the marker sets the role and leaves the name.
        night.artists.map { it.name to it.role } shouldContainExactly listOf("Practically Married Quartet" to "LIVE")
    }

    @Test
    fun `reads the host and the acts they play with`() {
        // "hosted by Nicole M Pikole w/ KumKween & Slaxy Lexy"
        val night = everything.on(LocalDate.of(2026, 7, 3), "CHANDELIER: JUST ONE DRINK")
        night.artists.map { it.name to it.role } shouldContainExactly
            listOf("Nicole M Pikole" to "HEADLINER", "KumKween" to "DJ", "Slaxy Lexy" to "DJ")
    }

    @Test
    fun `strips the trailing host annotation`() {
        val night = everything.on(LocalDate.of(2026, 8, 28), "CHANDELIER: CULT OF DRAG")
        night.artists.first().name shouldBe "Fagatta"
        night.artists.first().role shouldBe "HEADLINER"
    }

    @Test
    fun `opens a slot for each side of a b2b billing`() {
        // "Hyperbole b2b Mobutai, Anjawah b2b NeZoomie, KAT:10"
        val night = everything.on(LocalDate.of(2026, 7, 31), "LIFTED ✧ STAYGOLD ✧ RUMORE")
        night.artists.map { it.name } shouldContainExactly
            listOf("Hyperbole", "Mobutai", "Anjawah", "NeZoomie", "KAT:10")
    }

    @Test
    fun `keeps a band name whose conjunction is not a co-bill`() {
        // "Ali Affleck & The Traveling Janes (live)" is one act, not two.
        val night = everything.on(LocalDate.of(2026, 7, 7), "BELLMER BALBOA")
        night.artists.map { it.name } shouldContainExactly listOf("Ali Affleck & The Traveling Janes")
    }

    @Test
    fun `drops the venue's lineup fillers`() {
        // A withheld lineup, a bare "tba", and the open-decks night's activity list are not acts.
        everything.on(LocalDate.of(2026, 8, 29), "STAY TOXIC").artists.shouldBeEmpty()
        everything.on(LocalDate.of(2026, 8, 30), "DYKE GOTH NIGHT").artists.shouldBeEmpty()
        everything.on(LocalDate.of(2026, 8, 5), "OPEN DECKS FOR FLINTA* PING PONG FOR ALL").artists.shouldBeEmpty()
        everything.on(LocalDate.of(2026, 7, 8), "OPEN DECKS FOR FLINTA* - PING PONG FOR ALL").artists.shouldBeEmpty()
        // "… Steele and open decks slot" keeps the DJ and drops the slot.
        everything.on(LocalDate.of(2026, 7, 10), "RAMA FOREVER").artists.map { it.name } shouldContainExactly
            listOf("Somme F`arris", "Sherø Triqi", "Steele")
    }

    @Test
    fun `returns no events for a page without a programme`() {
        val document = Jsoup.parse("<html><body><main></main></body></html>", sourceUrl)
        CrackBellmerOverviewPageScraper(captureDate).scrape(document, sourceUrl).shouldBeEmpty()
    }

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
