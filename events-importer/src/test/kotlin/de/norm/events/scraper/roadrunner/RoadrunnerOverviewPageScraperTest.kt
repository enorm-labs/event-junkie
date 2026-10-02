package de.norm.events.scraper.roadrunner

import de.norm.events.scraper.AcceptedLimitations
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [RoadrunnerOverviewPageScraper].
 *
 * Uses a fixed clock (2026-05-01) so weekday-based year inference is deterministic, and
 * parses the real retro `programm.html` snapshot plus a synthetic multi-event fragment to
 * exercise the dotted-separator splitting. The scraper returns every dated block as-is;
 * dropping past-dated events is the persistence layer's concern (`EventUpsertService`).
 */
class RoadrunnerOverviewPageScraperTest {
    private val baseUrl = "http://www.roadrunners-paradise.de/programm.html"

    // Pin the clock before every fixture date so the year inferred from "Freitag, 29. Mai"
    // is deterministic (29 May 2026 = Friday) and the event counts as upcoming, not dropped.
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC)
    private val scraper = RoadrunnerOverviewPageScraper(clock)

    private fun programme() =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/roadrunner/roadrunner-programm.html")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    @Test
    fun `stores every night Rock, the venue's sound, since it names no style`() {
        AcceptedLimitations.withHouseGenre(EventSource.ROADRUNNER, scraper.scrape(programme(), baseUrl)).map { it.genre }.distinct() shouldBe listOf("Rock")
    }

    @Test
    fun `parses the single dated event from the retro programme page`() {
        val events = scraper.scrape(programme(), baseUrl)
        events shouldHaveSize 1

        val event = events.single()
        event.title shouldBe "BERLIN FREAK BURLESQUE CIRCUS"
        // "Freitag, 29. Mai" with no year → 2026 (the year 29 May falls on a Friday nearest today).
        event.eventDate shouldBe LocalDate.of(2026, 5, 29)
        event.doorsTime shouldBe LocalTime.of(20, 0)
        event.ticketUrl.shouldNotBeNull()
        event.ticketUrl shouldStartWith "https://www.eventbrite.de/e/berlin-freak-burlesque-circus"
        event.imageUrl shouldBe
            "http://www.roadrunners-paradise.de/Images/Programm/651910588_2774061836264243_4989135014726224547_n.jpg"
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "roadrunner:2026-05-29-berlin-freak-burlesque-circus"
        event.description.shouldNotBeNull()
        event.description shouldContain "LINE-UP"
        // Header intro and the flyer/ticket lines must not leak into the description.
        event.description shouldContain "Supported by MARIA"
    }

    @Test
    fun `reads date lines typed without the comma or the day's dot`() {
        // The autumn 2026 page writes "Samstag 05 September:" and "Samstag 31 Oktober:" beside the
        // canonical "Mittwoch, 11. November:"; the strict pattern found none of them (#1130).
        val autumnClock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)
        val events =
            RoadrunnerOverviewPageScraper(autumnClock).scrape(
                Jsoup.parse(
                    javaClass.classLoader
                        .getResourceAsStream("scraper/roadrunner/roadrunner-programm-autumn.html")!!
                        .bufferedReader()
                        .readText(),
                    baseUrl
                ),
                baseUrl
            )

        events.map { it.eventDate } shouldBe
            listOf(
                LocalDate.of(2026, 9, 5),
                LocalDate.of(2026, 9, 12),
                LocalDate.of(2026, 9, 13),
                LocalDate.of(2026, 10, 3),
                LocalDate.of(2026, 10, 9),
                LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 11, 11)
            )
        val blutUndEisen = events.first()
        blutUndEisen.title shouldBe "BLUT & EISEN 30 JAHRE"
        blutUndEisen.doorsTime shouldBe LocalTime.of(20, 0)
        events.last().title shouldBe "PHIL CAMPELL'S BASTARD SONS The Phil Forever Tour"
    }

    private fun october(): List<ScrapedEvent> =
        RoadrunnerOverviewPageScraper(Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC)).scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/roadrunner/roadrunner-programm-october.html")!!
                    .bufferedReader()
                    .readText(),
                baseUrl
            ),
            baseUrl
        )

    private fun ScrapedEvent.billing() = artists.map { it.name to it.role }

    @Test
    fun `bills a named night's Live acts and its Record Hop DJ, under the night's name`() {
        val night = october().first()

        night.title shouldBe "40 Jahre Louisiana Rebs Berlin"
        night.billing() shouldBe
            listOf("THE JETS" to "HEADLINER", "SMOKESTACK LIGHTNIN’" to "HEADLINER", "The Louisiana Wax Team" to "DJ")
        night.description.shouldNotBeNull() shouldStartWith "Qua Alter schon fast"
        night.description shouldNotContain "Live:"
        night.description shouldNotContain "Record Hop:"
    }

    @Test
    fun `bills a Support act under its label and keeps a bracketed note out of the line-up`() {
        val samhain = october().single { it.eventDate == LocalDate.of(2026, 10, 31) }

        samhain.title shouldBe "AT THE NIGHT OF SAMHAIN"
        samhain.billing() shouldBe listOf("THE CLOVERHEARTS" to "HEADLINER", "The STINKY PINKY’S" to "SUPPORT")
        samhain.description shouldNotContain "Support:"
    }

    @Test
    fun `splits acts run together behind origin tags and reads a continued line and a dee-jay prefix`() {
        val jukeJoint = october().single { it.eventDate == LocalDate.of(2026, 10, 16) }

        jukeJoint.title shouldBe "THE JUKE JOINT DANCE PARTY"
        jukeJoint.billing() shouldBe
            listOf(
                "KEITH DUNN" to "HEADLINER",
                "SAUDIA YOUNG" to "HEADLINER",
                "LARS VEGAS & BAND" to "HEADLINER",
                "Red Rockin'" to "DJ"
            )
    }

    @Test
    fun `bills no act for a block with no line-up label, and declares that as a limitation`() {
        // The title alone bills a band battle (`BOWIE 10`) or a misspelled act with its tour run on (#2369).
        val unlabelled = october().filter { it.eventDate in setOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 11, 11)) }

        unlabelled.map { it.title } shouldBe listOf("BOWIE 10", "PHIL CAMPELL'S BASTARD SONS The Phil Forever Tour")
        unlabelled.flatMap { it.artists } shouldBe emptyList()
        AcceptedLimitations.forSource(EventSource.ROADRUNNER).map { it.aspect } shouldContain LimitedAspect.ARTISTS
    }

    @Test
    fun `keeps every sourceId it had when the act line was the title, so no row is re-keyed`() {
        october().map { it.sourceId } shouldBe
            listOf(
                "roadrunner:2026-10-03-the-jets-uk-smokestack-lightnin",
                "roadrunner:2026-10-09-bowie-10",
                "roadrunner:2026-10-16-the-juke-joint-dance-party",
                "roadrunner:2026-10-31-the-cloverhearts",
                "roadrunner:2026-11-11-phil-campell-s-bastard-sons-the-phil-forever-tour"
            )
    }

    @Test
    fun `reads an inline Live list, and a Support line as confirming the title is the act`() {
        val autumnClock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)
        val events =
            RoadrunnerOverviewPageScraper(autumnClock).scrape(
                Jsoup.parse(
                    javaClass.classLoader
                        .getResourceAsStream("scraper/roadrunner/roadrunner-programm-autumn.html")!!
                        .bufferedReader()
                        .readText(),
                    baseUrl
                ),
                baseUrl
            )

        events[0].billing() shouldBe listOf("UNSTRUT" to "HEADLINER", "BOXI BARRÉ" to "HEADLINER", "NERVE CENTER" to "HEADLINER")
        events[1].billing() shouldBe listOf("HERBST IN PEKING" to "HEADLINER", "TARWATER" to "HEADLINER")
        val lazys = events[2]
        lazys.billing() shouldBe listOf("THE LAZYS" to "HEADLINER", "SWEET ELECTRIC" to "SUPPORT")
        lazys.artists.first().titleDerived shouldBe true
    }

    @Test
    fun `splits multiple dot-separated blocks and infers each year from its weekday`() {
        val html =
            """
            <html><body>
              <p class="Stil62">Kartenreservierungen — header, no date, must be ignored.</p>
              <p class="Stil62">. . . . . . . . . . . . . . . . . . . .</p>
              <p class="Stil62">Samstag, 4. Juli:</p>
              <p class="Stil62"><span class="Stil11">THE ROCKABILLY KINGS</span></p>
              <p class="Stil62">Einlass: 21:00 Uhr</p>
              <p class="Stil65">A rowdy night of roots rock.</p>
              <p class="Stil62">. . . . . . . . . . . . . . . . . . . .</p>
              <p class="Stil62">Freitag, 14. August:</p>
              <p class="Stil62"><span class="Stil11">BLUES EXPLOSION</span></p>
              <p class="Stil62">Einlass: 20:00 Uhr</p>
              <p class="Stil62">. . . . . . . . . . . . . . . . . . . .</p>
              <p class="Stil62">Roadrunners Rock Motor Club — footer, no date.</p>
            </body></html>
            """.trimIndent()

        val events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
        events shouldHaveSize 2

        events[0].title shouldBe "THE ROCKABILLY KINGS"
        events[0].eventDate shouldBe LocalDate.of(2026, 7, 4) // Saturday
        events[0].doorsTime shouldBe LocalTime.of(21, 0)
        events[0].description shouldBe "A rowdy night of roots rock."

        events[1].title shouldBe "BLUES EXPLOSION"
        events[1].eventDate shouldBe LocalDate.of(2026, 8, 14) // Friday
        events[1].description shouldBe null
    }

    @Test
    fun `returns no events when the page has no dated blocks`() {
        val html =
            """
            <html><body>
              <p>Welcome to Roadrunner's Paradise</p>
              <p>. . . . . . . . . .</p>
              <p>Kartenreservierungen: info@roadrunners-paradise.de</p>
            </body></html>
            """.trimIndent()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl) shouldHaveSize 0
    }
}
