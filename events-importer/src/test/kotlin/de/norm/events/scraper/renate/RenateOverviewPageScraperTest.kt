package de.norm.events.scraper.renate

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [RenateOverviewPageScraper].
 *
 * Uses a real homepage snapshot pinned to a fixed clock, since the programme prints no year. Most
 * of these tests exist because the venue reuses `<strong>` and `<p>` for several different things:
 * the same markup carries floor headings, a slogan, host credits, a workshop timetable and pages
 * of club policy, and only the floor names and line lengths tell them apart.
 */
class RenateOverviewPageScraperTest {
    /** Pinned to the fixture's capture date so the weekday-based year inference is stable. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-01T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = RenateOverviewPageScraper(clock)
    private val baseUrl = "https://www.renate.cc/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/renate/renate-overview.html")!!
                .bufferedReader()
                .readText()
        events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(
        date: LocalDate,
        titlePrefix: String
    ): ScrapedEvent = events.first { it.eventDate == date && it.title.startsWith(titlePrefix) }

    private fun stageOf(
        event: ScrapedEvent,
        stage: String
    ): List<String> = event.artists.filter { it.stage == stage }.map { it.name }

    @Test
    fun `extracts every event row, excluding the trailing blog post`() {
        // The page ends with a `.prog-row.blog-row` news item that carries no date.
        events shouldHaveSize 14
    }

    @Test
    fun `parses a night's date, ticket link and identity`() {
        val night = event(LocalDate.of(2026, 8, 6), "Renate x Neer")
        night.eventType shouldBe "PARTY"
        night.startTime.shouldBeNull()
        night.sourceUrl shouldBe baseUrl
        night.sourceId shouldBe "renate:2026-08-06-renate-x-neer-x-kollektiv-lost-in"
        night.ticketUrl shouldBe "https://ra.co/events/2485741"
    }

    @Test
    fun `groups the DJs by the floor they play on`() {
        val night = event(LocalDate.of(2026, 8, 7), "Renate Klubnacht")
        stageOf(night, "GARDEN") shouldContainExactly listOf("SERA (Live)")
        stageOf(night, "GREEN") shouldContainExactly listOf("Cleymoore", "NOB", "So-Fi")
        stageOf(night, "BLACK") shouldContainExactly listOf("Delta Division", "Mruda", "N ska")
    }

    @Test
    fun `splits the floors even when a night packs them into one paragraph`() {
        // This night's whole lineup is one <p> with <br> breaks, headings included; a
        // paragraph-per-floor assumption would file every act under the first floor.
        val night = event(LocalDate.of(2026, 8, 1), "Renate Klubnacht")
        stageOf(night, "GARDEN") shouldContainExactly listOf("EMIRA", "Leo Roskovec", "Marco Lenko", "Tristan Blach")
        stageOf(night, "GREEN") shouldContainExactly listOf("Clovis", "Edgar Peng", "Enzio Etchaberri", "Lush Lab")
        stageOf(night, "BLACK") shouldContainExactly listOf("CSILLA", "Heka", "Maris Shilton", "Pachka")
    }

    @Test
    fun `does not read the venue's slogan as a floor`() {
        // "Garten für alle!" is a <strong> heading on five nights, and the garden floor is spelled
        // GARDEN — so the German spelling must not open a stage.
        events.flatMap { it.artists }.map { it.stage }.distinct().forEach { stage ->
            (stage in setOf("GARDEN", "GREEN", "BLACK", "RED", "SECRET", "TOP SECRET", "CLUB")) shouldBe true
        }
    }

    @Test
    fun `does not read a host credit as an act`() {
        // "hosted by Neer" follows the GARDEN heading as its own <strong> line.
        val night = event(LocalDate.of(2026, 8, 6), "Renate x Neer")
        stageOf(night, "GARDEN") shouldContainExactly listOf("Jan Weber", "Smoothie Operator")
        events.flatMap { it.artists }.none { it.name.contains("hosted by", ignoreCase = true) } shouldBe true
    }

    @Test
    fun `does not read the workshop timetable or club policy as acts`() {
        val night = event(LocalDate.of(2026, 8, 22), "Renate Garten")
        stageOf(night, "GARDEN") shouldContainExactly
            listOf("Sameer Rahat (live)", "Pés de Barro (live Band)", "O.M.Theorem")
        // The policy block is repeated verbatim on every night and holds no lineup.
        events.flatMap { it.artists }.none { it.name.contains("Respect") } shouldBe true
        events.flatMap { it.artists }.none { it.name.startsWith("Workshops") } shouldBe true
    }

    @Test
    fun `drops the venue's unannounced-act placeholder`() {
        val night = event(LocalDate.of(2026, 8, 29), "Renate Klubnacht")
        stageOf(night, "GREEN") shouldContainExactly listOf("Amy Dabbs", "Gabriel Muñoz")
        events.flatMap { it.artists }.none { it.name.contains("tba", ignoreCase = true) } shouldBe true
    }

    @Test
    fun `keeps an act billed on two floors only once`() {
        // "Remoto Records" hosts both the garden and the green floor that night. Storing it twice
        // would violate the event_artist unique constraint on (event_id, artist_id) and fail the
        // whole import, so the first billing wins.
        val night = event(LocalDate.of(2026, 8, 27), "Renate Klubnacht")
        night.artists.map { it.name } shouldContainExactly listOf("Remoto Records")
        night.artists.single().stage shouldBe "GARDEN"
    }

    @Test
    fun `never bills one act twice on the same night`() {
        events.forEach { e -> e.artists.map { it.name.lowercase() }.distinct() shouldHaveSize e.artists.size }
    }

    @Test
    fun `yields no artists for a night whose text is only prose`() {
        // The festival night describes itself in paragraphs and names no floor at all.
        event(LocalDate.of(2026, 8, 21), "The Village Festival").artists.shouldBeEmpty()
    }

    @Test
    fun `reads a bare line-up under a single space badge, and leaves prose under one alone`() {
        // SENSUS names no floor: the CLUB badge is the stage and every line is a DJ (#1582).
        val sensus = event(LocalDate.of(2026, 9, 18), "SENSUS")
        sensus.artists.map { it.stage }.distinct() shouldContainExactly listOf("CLUB")
        stageOf(sensus, "CLUB") shouldContainExactly
            listOf(
                "DJ Fuckoff",
                "DJ Fucks Himself",
                "P.Vanillaboy",
                "Penglord",
                "PAU",
                "Perra Inmunda",
                "FORTUNATA",
                "DJ Buona Sara",
                "Katta Lana",
                "Brauer",
                "Lenny Fuck",
                "Josi Miller",
                "Carl Hang",
                "MC Chorizo y Gringo"
            )
    }

    @Test
    fun `reads the birthday night's secret floor`() {
        val birthday = event(LocalDate.of(2026, 9, 11), "19 Years Renate")
        stageOf(birthday, "SECRET") shouldContainExactly listOf("Johnny Knüppel Takeover")
        birthday.artists.map { it.stage }.distinct() shouldContainExactly
            listOf("GARDEN", "GREEN", "RED", "BLACK", "SECRET")
    }

    @Test
    fun `infers the year from the weekday and keeps the listing chronological`() {
        events.map { it.eventDate } shouldBe events.map { it.eventDate }.sorted()
        events.first().eventDate shouldBe LocalDate.of(2026, 8, 1)
        events.last().eventDate shouldBe LocalDate.of(2026, 9, 18)
    }

    @Test
    fun `publishes no prices or images`() {
        events.all { it.pricePresale == null && it.priceBoxOffice == null && it.imageUrl == null } shouldBe true
    }

    @Test
    fun `bills every act as a DJ`() {
        events.flatMap { it.artists }.all { it.role == "DJ" } shouldBe true
    }

    @Test
    fun `returns no events for a page without a programme`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
