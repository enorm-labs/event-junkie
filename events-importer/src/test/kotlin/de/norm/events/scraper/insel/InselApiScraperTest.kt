package de.norm.events.scraper.insel

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [InselApiScraper].
 *
 * Driven by the venue's real Gatsby static-query artefact, trimmed to the 39 upcoming events plus
 * the last two past ones (the live artefact carries the whole archive back to 2022 — 444 events —
 * and its responsive `srcSet` strings, none of which the parser reads). A fixed [Clock] pins the
 * past-event cutoff so the fixture stays deterministic.
 *
 * The fixture already covers the shapes that matter: separate and collapsed doors/start billings,
 * the `19 Uhr` spelling without minutes and the `20,00 Uhr` comma typo, a free Sunday matinée, a
 * sold-out show marked in both the title and the prose, a closed private function, three title
 * frames (`w/`, `•`, a plain act name), country tags on act names, three support-billing spellings,
 * a poetry slam, and two events whose description prints a date months off their real one.
 */
class InselApiScraperTest {
    private val scraper = InselApiScraper(Clock.fixed(Instant.parse("2026-08-06T09:00:00Z"), BERLIN))
    private val sourceUrl = "https://www.inselberlin.de/"
    private lateinit var events: List<ScrapedEvent>

    private fun fixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("scraper/insel/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        events = scraper.scrape(fixture("insel-events.json"), sourceUrl)!!
    }

    @Test
    fun `drops the archive and keeps only upcoming events`() {
        events shouldHaveSize 39
        events.first().eventDate shouldBe LocalDate.of(2026, 8, 9)
        events.last().eventDate shouldBe LocalDate.of(2027, 1, 22)
    }

    @Test
    fun `parses every field of a promoted concert`() {
        val polica = events.single { it.title == "Internal Bleeding (US)" }
        polica.eventDate shouldBe LocalDate.of(2026, 8, 20)
        polica.doorsTime shouldBe LocalTime.of(19, 0)
        polica.startTime shouldBe LocalTime.of(20, 0)
        polica.eventType shouldBe "CONCERT"
        polica.sourceUrl shouldBe sourceUrl
        polica.sourceId shouldBe "insel:2026-08-20-internal-bleeding-us"
        polica.ticketUrl!! shouldContain "atokberlin.stager.co/shop/default/events/111656142"
        polica.imageUrl!! shouldContain "datocms-assets.com"
        polica.promoters shouldContainExactly listOf("ATOK")
        polica.free shouldBe false
        polica.soldOut shouldBe false
        polica.status shouldBe "SCHEDULED"
        // The country tag is provenance, not part of the act's name — the title keeps it, the artist does not.
        polica.artists shouldContainExactly listOf(ScrapedArtist("Internal Bleeding", "HEADLINER"))
        // The venue publishes no prices at all.
        polica.pricePresale.shouldBeNull()
        polica.priceBoxOffice.shouldBeNull()
        polica.priceNote.shouldBeNull()
        polica.genre.shouldBeNull()
    }

    @Test
    fun `strips the metadata lines the fields already carry out of the description`() {
        val polica = events.single { it.title == "Internal Bleeding (US)" }
        // The description is nothing but the promoter, act, date, times and ticket call to action.
        polica.description.shouldBeNull()

        // Bound once: `shouldNotBeNull()` smart-casts, so repeating `!!` per assertion only earns
        // an "unnecessary non-null assertion" warning.
        val y2kDescription = events.single { it.title == "Y2K Nostalgia Soundtrack" }.description.shouldNotBeNull()
        y2kDescription shouldContain "BERLIN NU-WAVE"
        y2kDescription shouldNotContain "Einlass"
        y2kDescription shouldNotContain "TICKETS GIBT ES HIER"
        y2kDescription shouldNotContain "ATOK prs."
    }

    @Test
    fun `takes the date only from the node instant, never from the stale prose`() {
        // Both descriptions print a date months away from the event's real one.
        events.single { it.title == "Insel Slam" }.eventDate shouldBe LocalDate.of(2026, 8, 26)
        events.single { it.title == "George JR" }.eventDate shouldBe LocalDate.of(2026, 10, 1)
    }

    @Test
    fun `uses the node time as the start when the venue bills no doors`() {
        val sameen = events.single { it.title == "Sameen Qasim" }
        sameen.doorsTime.shouldBeNull()
        sameen.startTime shouldBe LocalTime.of(16, 0)
        sameen.free shouldBe true
        sameen.promoters shouldContainExactly listOf("Kulturalarm")
        // A free matinée has no ticket shop; the only link in the blurb is a YouTube video.
        sameen.ticketUrl.shouldBeNull()
    }

    @Test
    fun `reads the minute-less and comma-typo time spellings`() {
        // "Einlass: 19 Uhr" / "Beginn: 20 Uhr"
        val marlin = events.single { it.title == "Marlin Beach" }
        marlin.doorsTime shouldBe LocalTime.of(19, 0)
        marlin.startTime shouldBe LocalTime.of(20, 0)
        // "Beginn 20,00 Uhr"
        val troops = events.single { it.title == "The Troops Of Doom" }
        troops.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `marks a sold-out show and strips the marker from title and identity`() {
        val deadPhoenix = events.single { it.eventDate == LocalDate.of(2026, 12, 5) }
        deadPhoenix.title shouldBe "Dead Phoenix - BERLIN"
        deadPhoenix.soldOut shouldBe true
        deadPhoenix.sourceId shouldBe "insel:2026-12-05-dead-phoenix-berlin"
    }

    @Test
    fun `imports a closed private function without minting it as an artist`() {
        val closed = events.single { it.title.startsWith("Sommerfest") }
        closed.eventType shouldBe "OTHER"
        closed.artists.shouldBeEmpty()
        // An all-day entry carries no meaningful clock time.
        closed.doorsTime.shouldBeNull()
        closed.startTime.shouldBeNull()
        closed.description!! shouldContain "Firmenfeier geschlossen"
    }

    @Test
    fun `unpacks a w-slash billing into its acts rather than storing the night's name`() {
        val riot = events.single { it.title.startsWith("RIOT ON THE ISLAND") }
        riot.artists shouldContainExactly
            listOf(
                ScrapedArtist("Them Spirals", "HEADLINER"),
                ScrapedArtist("Painted Lox's", "HEADLINER"),
                ScrapedArtist("AK In Control", "HEADLINER")
            )
    }

    @Test
    fun `takes the first bullet segment of a title as the act`() {
        val juneCoco = events.single { it.title.startsWith("June Cocó") }
        juneCoco.artists shouldContainExactly listOf(ScrapedArtist("June Cocó", "HEADLINER"))
    }

    @Test
    fun `splits a co-billed title and strips the country tags`() {
        val graupause = events.single { it.eventDate == LocalDate.of(2026, 9, 18) }
        graupause.title shouldBe "Graupause (DE) + Waarz Up (Bln)"
        graupause.artists shouldContainExactly
            listOf(
                ScrapedArtist("Graupause", "HEADLINER"),
                ScrapedArtist("Waarz Up", "HEADLINER")
            )
        graupause.promoters shouldContainExactly listOf("Punkfilmfestival Berlin")
    }

    @Test
    fun `reads a support billing in both of the venue's colon spellings`() {
        val feePenafiel = events.single { it.title == "Fee Penafiel" }
        feePenafiel.subtitle shouldBe "+ support: Karwendel"
        feePenafiel.artists shouldContainExactly
            listOf(
                ScrapedArtist("Fee Penafiel", "HEADLINER"),
                ScrapedArtist("Karwendel", "SUPPORT")
            )

        val dieWeiteren = events.single { it.title == "Die Weiteren Aussichten" }
        dieWeiteren.artists shouldContainExactly
            listOf(
                ScrapedArtist("Die Weiteren Aussichten", "HEADLINER"),
                ScrapedArtist("Alles Karo", "SUPPORT")
            )

        // Run together with the act's name on one line, which the marker still finds.
        events.single { it.title == "Marlin Beach" }.artists shouldContainExactly
            listOf(
                ScrapedArtist("Marlin Beach", "HEADLINER"),
                ScrapedArtist("Mellow Ma", "SUPPORT")
            )
    }

    @Test
    fun `types a poetry slam as a reading and bills no artist for it`() {
        val slam = events.single { it.title == "Insel Slam" }
        slam.eventType shouldBe "READING"
        slam.artists.shouldBeEmpty()
        slam.promoters shouldContainExactly listOf("Kunst&Krawall")
    }

    @Test
    fun `reads the ticket link by its anchor text, not its host`() {
        // Every shop is a different host; the emoji-wrapped call to action is found the same way.
        val aoxo = events.first { it.title == "AoxoToxoA (CH)" }
        aoxo.ticketUrl shouldBe "https://www.tickettailor.com/events/gratefultrips/2097651"
        aoxo.artists shouldContainExactly listOf(ScrapedArtist("AoxoToxoA", "HEADLINER"))
        events.single { it.title == "Jive Mother Mary" }.ticketUrl!! shouldContain "dice.fm"
    }

    @Test
    fun `gives every event a source id prefixed with the enum value`() {
        events.all { it.sourceId.startsWith("insel:") } shouldBe true
        events.map { it.sourceId }.toSet() shouldHaveSize events.size
    }

    @Test
    fun `returns null for a static query that is not the programme`() {
        // A sibling query publishes the same collection projected down to bare dates and categories.
        scraper.scrape(fixture("insel-dates-only.json"), sourceUrl).shouldBeNull()
        scraper.scrape("""{"data":{"datoCmsFooter":{"address":"…"}}}""", sourceUrl).shouldBeNull()
        scraper.scrape("not json at all", sourceUrl).shouldBeNull()
    }

    @Test
    fun `returns an empty list for the programme query with no events`() {
        scraper.scrape("""{"data":{"allDatoCmsEvent":{"edges":[]}}}""", sourceUrl).shouldNotBeNull().shouldBeEmpty()
    }

    @Test
    fun `skips an event with no name or no parseable time`() {
        val json =
            """
            {"data":{"allDatoCmsEvent":{"edges":[
              {"node":{"name":"Real Act","time":"2026-09-01T20:00:00+02:00"}},
              {"node":{"name":"  ","time":"2026-09-02T20:00:00+02:00"}},
              {"node":{"name":"No Date","time":"soon"}}
            ]}}}
            """.trimIndent()
        val parsed = scraper.scrape(json, sourceUrl).shouldNotBeNull()
        parsed shouldHaveSize 1
        parsed[0].title shouldBe "Real Act"
        parsed[0].startTime shouldBe LocalTime.of(20, 0)
    }
}
