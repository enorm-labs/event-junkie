package de.norm.events.scraper.gretchen

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [GretchenOverviewPageScraper].
 *
 * Uses a saved snapshot of the real Gretchen homepage as a regression fixture.
 * Gretchen renders full four-digit-year dates, so no clock injection is needed.
 */
class GretchenOverviewPageScraperTest {
    private val baseUrl = "https://www.gretchen-club.de/"
    private val scraper = GretchenOverviewPageScraper()
    private lateinit var html: String

    @BeforeEach
    fun setUp() {
        html =
            javaClass.classLoader
                .getResourceAsStream("scraper/gretchen/gretchen-overview.html")!!
                .bufferedReader()
                .readText()
    }

    private fun scrape(): List<ScrapedEvent> = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)

    private fun eventWithId(id: String) = scrape().first { it.sourceId == "gretchen:$id" }

    @Test
    fun `scrape extracts every gig block from the fixture`() {
        scrape() shouldHaveSize 92
    }

    @Nested
    inner class ConcertParsing {
        @Test
        fun `parses a fully populated concert`() {
            val event = eventWithId("3492")

            event.title shouldBe "TULIPA RUIZ"
            event.genre shouldBe "MPB, Pop, Electronica"
            event.eventDate shouldBe LocalDate.of(2026, 7, 10)
            event.doorsTime shouldBe LocalTime.of(19, 30)
            event.startTime shouldBe LocalTime.of(20, 30)
            event.sourceUrl shouldBe "https://www.gretchen-club.de/detail.php?id=3492"
            event.ticketUrl shouldBe "https://ra.co/events/2429097"
            event.status shouldBe "SCHEDULED"
            event.free shouldBe false
        }

        @Test
        fun `extracts the lineup performer as headliner`() {
            eventWithId("3492").artists shouldContainExactly listOf(ScrapedArtist("Tulipa Ruiz", "HEADLINER"))
        }

        @Test
        fun `parses tiered presale and box-office prices`() {
            val event = eventWithId("3492")

            event.pricePresale shouldBe BigDecimal("12")
            event.priceBoxOffice shouldBe BigDecimal("30")
            event.priceNote shouldContain "Vorverkauf 12"
            event.priceNote shouldContain "Abendkasse 30"
        }

        @Test
        fun `resolves and percent-encodes the poster image URL`() {
            val imageUrl = eventWithId("3492").imageUrl!!

            imageUrl shouldContain "https://www.gretchen-club.de/bilder_upload/69ef32032c99b"
            imageUrl shouldContain "%20" // filename contains spaces
        }

        @Test
        fun `drops the venue itself as a promoter`() {
            // "Veranstalter*in: Gretchen" is the venue organising its own night, not an external promoter.
            eventWithId("3492").promoters.shouldBeEmpty()
        }
    }

    @Nested
    inner class ClubNightParsing {
        @Test
        fun `parses a multi-stage lineup in billing order`() {
            val event = eventWithId("3536")

            // Party-style title is kept verbatim; performers come from the .lineup stages, deduplicated.
            event.title shouldBe "saHHara x UNDERGA33 present: DAZE"
            event.artists shouldContainExactly
                listOf(
                    ScrapedArtist("Gnawa Vibes", "HEADLINER"),
                    ScrapedArtist("HearThug", "SUPPORT"),
                    ScrapedArtist("saHHar", "SUPPORT"),
                    ScrapedArtist("Rabibti áTable", "SUPPORT"),
                    ScrapedArtist("Aalia", "SUPPORT"),
                    ScrapedArtist("DINA", "SUPPORT"),
                    ScrapedArtist("Mefteh", "SUPPORT")
                )
        }

        @Test
        fun `keeps an external promoter and omits a missing show time`() {
            val event = eventWithId("3536")

            event.promoters shouldContainExactly listOf("saHHara")
            event.doorsTime shouldBe LocalTime.of(23, 59)
            event.startTime.shouldBeNull()
        }

        @Test
        fun `leaves box-office price null when the source says tba`() {
            val event = eventWithId("3536")

            event.pricePresale shouldBe BigDecimal("10")
            event.priceBoxOffice.shouldBeNull()
        }
    }

    @Nested
    inner class LineupCleaning {
        @Test
        fun `strips bold floor headers instead of fusing them into the first act`() {
            // BOX1 "<b>AFRO FLOOR</b>MaVert", BOX2 "<b>LATIN FLOOR</b>DJ Ebbsolute"; "+ special guests" dropped.
            eventWithId("3537").artists shouldContainExactly
                listOf(
                    ScrapedArtist("MaVert", "HEADLINER"),
                    ScrapedArtist("Tommy Tea", "SUPPORT"),
                    ScrapedArtist("DJ Ebbsolute", "SUPPORT"),
                    ScrapedArtist("Juliany LSP", "SUPPORT"),
                    ScrapedArtist("Juan Virviescas", "SUPPORT")
                )
        }

        @Test
        fun `drops host and visuals credits and member lists, keeps the opening DJ`() {
            // Trailing lineup mixes "Hosted by …", a "(Bass)/(Keys)/(Drums)" member list, and "Opening DJ-Set by …".
            eventWithId("3531").artists shouldContainExactly
                listOf(
                    ScrapedArtist("Peter Somuah", "HEADLINER"),
                    ScrapedArtist("Skyline Sun", "SUPPORT"),
                    ScrapedArtist("Outta Line", "SUPPORT"),
                    ScrapedArtist("Allynx", "SUPPORT"),
                    ScrapedArtist("Sean Steinfeger", "SUPPORT")
                )
        }

        @Test
        fun `strips a support prefix and drops the rescheduled-date note`() {
            // Trailing lineup: "Support: Steinza" + "Ersatztermin vom 11.02.2026".
            eventWithId("3450").artists shouldContainExactly
                listOf(
                    ScrapedArtist("Matt Maeson", "HEADLINER"),
                    ScrapedArtist("Steinza", "SUPPORT")
                )
        }

        @Test
        fun `does not mint credit lines, floor headers or notes as artists`() {
            val allArtists = scrape().flatMap { it.artists }.map { it.name }
            allArtists.forEach { name ->
                name shouldNotContain "FLOOR"
                name shouldNotContain "Ersatztermin"
                name shouldNotContain "Hosted by"
                name shouldNotContain "Live Visuals"
                name shouldNotContain "Opening DJ-Set"
                name shouldNotContain "verlegt"
            }
        }

        // The venue writes its relocation note two ways. The sentence form ("Die Show wird aus dem
        // Gretchen in das Metropol verlegt.") is long enough for the ten-word prose guard, but the
        // terse form is only three words and was billed as a support act — a live import stored
        // "verlegt vom Frannz" as an artist of the relocated Mad Tsai show.
        @Test
        fun `drops a terse relocation note that is too short for the prose guard`() {
            val html =
                """
                <div class="gig">
                    <div class="gig_top">
                        <span class="date">Sa. <strong>26.09.2026</strong><br> Doors: 19.30 <br/>Show: 20.30</span>
                    </div>
                    <div class="gig_main"><div class="scroll nano"><div class="text nano-content">
                        <span class="title">Rap<h2><a href="detail.php?id=9001">Trinity presents: MAD TSAI - The Bite Back Tour</a></h2></span>
                        <span class="box"></span><span class="lineup"><p>Mad Tsai<br/></p></span>
                        <span class="box"></span><span class="lineup">verlegt vom Frannz</span>
                    </div></div></div>
                </div>
                """.trimIndent()

            val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

            event.artists shouldContainExactly listOf(ScrapedArtist("Mad Tsai", "HEADLINER"))
        }

        @Test
        fun `strips a bare trailing DJ-Set suffix off a performer name`() {
            // "Acid Arab DJ-Set" / "Paty Vapor DJ-Set" are the acts "Acid Arab" / "Paty Vapor".
            eventWithId("3550").artists.first() shouldBe ScrapedArtist("Acid Arab", "HEADLINER")
            eventWithId("3560").artists shouldContainExactly
                listOf(
                    ScrapedArtist("System Olympia", "HEADLINER"),
                    ScrapedArtist("Paty Vapor", "SUPPORT")
                )
        }

        @Test
        fun `splits an inline feat credit into separate acts`() {
            // "Mop Mop ft. Anthony Joseph" → the main act plus its featured guest.
            eventWithId("3518").artists shouldContainExactly
                listOf(
                    ScrapedArtist("Mop Mop", "HEADLINER"),
                    ScrapedArtist("Anthony Joseph", "SUPPORT"),
                    ScrapedArtist("Phat Fred", "SUPPORT")
                )
            // A feat credit on a mid-lineup line splits too ("The Bug ft. Dis Fig"), and the
            // "w/" collaboration shorthand splits as well ("Tikiman w/Scion" → "Tikiman" + "Scion").
            eventWithId("3519").artists.map { it.name } shouldContainExactly
                listOf("Tikiman", "Scion", "JK Flesh", "The Bug", "Dis Fig", "Ghost Dubs", "Gorgonn")
        }

        @Test
        fun `recovers the headliner from a presents-title when the lineup is empty`() {
            // "Landstreicher presents: XAVI - Sorgenfrei Tour 2027" has a price-only lineup, so
            // the act is taken from the title: the "<promoter> presents:" prefix and the tour
            // tail are stripped down to "XAVI".
            eventWithId("3496").artists shouldContainExactly listOf(ScrapedArtist("XAVI", "HEADLINER"))
        }

        @Test
        fun `does not mint a compound event label as the fallback headliner`() {
            // "MIND Enterprises GmbH presents: WRESTLEFEST Europa - Opening Night" also has an
            // empty lineup, but the title remainder keeps a spaced dash (a compound event label,
            // not a clean act), so no artist is recovered.
            eventWithId("3546").artists.shouldBeEmpty()
        }

        @Test
        fun `drops a trailing plus-tag stylisation`() {
            // "Okvsho +experience" is the act "Okvsho"; "+experience" is a stylisation, not a second act.
            eventWithId("3453").artists shouldContainExactly
                listOf(
                    ScrapedArtist("Okvsho", "HEADLINER"),
                    ScrapedArtist("Sean Steinfeger", "SUPPORT")
                )
        }

        // detail.php?id=3543 and id=3571: the second `.lineup` block adds acts after the main
        // billing with a leading "+", and its <em> price note holds the only <br> between two lines.
        @Test
        fun `keeps a line break that sits inside an em and strips a leading plus`() {
            val html =
                """
                <div class="gig">
                    <div class="gig_top">
                        <span class="date">Do. <strong>05.11.2026</strong><br> Doors: 20.00</span>
                    </div>
                    <div class="gig_main"><div class="scroll nano"><div class="text nano-content">
                        <span class="title">Afro<h2><a href="detail.php?id=3543">Àbáse</a></h2></span>
                        <span class="box"></span><span class="lineup"><p>Àbáse  (Analogue Foundation & Oshu Records/HU/D ) *live*<br />ZENA    (Brownswood Rec./UK) *live*<br /></p></span>
                        <span class="box"></span><span class="lineup">+ guests<em><br /><br /></em>Opening DJ-Set by Sean Steinfeger<em><br /><br />*Vorverkauf 12 €/ 18 €/ 24 € zzgl. Gebühren * Abendkasse tba.*</em></span>
                        <span class="box"></span><span class="lineup">+ Mittelmeer Monologe</span>
                    </div></div></div>
                </div>
                """.trimIndent()

            val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

            event.artists.map { it.name } shouldContainExactly
                listOf("Àbáse", "ZENA", "Sean Steinfeger", "Mittelmeer Monologe")
        }

        // Production stored `Allynx b2b Sean Steinfeger` and `Slimzee b2b Grandmixxer` as one act each (#1844).
        @Test
        fun `splits a back-to-back slot into two acts`() {
            val html =
                """
                <div class="gig">
                    <div class="gig_top">
                        <span class="date">Sa. <strong>21.11.2026</strong><br> Doors: 23.00</span>
                    </div>
                    <div class="gig_main"><div class="scroll nano"><div class="text nano-content">
                        <span class="title">Dub<h2><a href="detail.php?id=3600">The Bug presents: Pressure</a></h2></span>
                        <span class="box"></span><span class="lineup"><p>The Bug<br />Slimzee b2b Grandmixxer<br /></p></span>
                    </div></div></div>
                </div>
                """.trimIndent()

            val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

            event.artists.map { it.name } shouldContainExactly listOf("The Bug", "Slimzee", "Grandmixxer")
        }

        // Production stored `Box 2: Bassism + Impulse Basskultur` as one act (#1843).
        @Test
        fun `drops a room label and splits the crews after it`() {
            val html =
                """
                <div class="gig">
                    <div class="gig_top">
                        <span class="date">Sa. <strong>21.11.2026</strong><br> Doors: 23.00</span>
                    </div>
                    <div class="gig_main"><div class="scroll nano"><div class="text nano-content">
                        <span class="title">Drum &amp; Bass<h2><a href="detail.php?id=3601">30 Years Recycle</a></h2></span>
                        <span class="box"></span><span class="lineup"><p>Congo Natty<br />Box 2: Bassism + Impulse Basskultur<br /></p></span>
                    </div></div></div>
                </div>
                """.trimIndent()

            val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

            event.artists.map { it.name } shouldContainExactly listOf("Congo Natty", "Bassism", "Impulse Basskultur")
        }

        // detail.php?id=3480 (#1761): the page opens `*live` and never closes it.
        @Test
        fun `strips a star marker the page never closed`() {
            val html =
                """
                <div class="gig">
                    <div class="gig_top">
                        <span class="date">Mi. <strong>07.10.2026</strong><br> Doors: 20.00</span>
                    </div>
                    <div class="gig_main"><div class="scroll nano"><div class="text nano-content">
                        <span class="title">Brasil<h2><a href="detail.php?id=3480">Sessa</a></h2></span>
                        <span class="box"></span><span class="lineup"><p>Sessa  (Mexican Summer/BR/US) *live*<br /></p></span>
                        <span class="box"></span><span class="lineup">Support: Tontom (BR) *live<em><br /><br />*Vorverkauf 25 € zzgl. Gebühren * Abendkasse 30 €*</em></span>
                    </div></div></div>
                </div>
                """.trimIndent()

            val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

            event.artists.map { it.name } shouldContainExactly listOf("Sessa", "Tontom")
        }
    }

    @Nested
    inner class TitleCleaning {
        @Test
        fun `strips the anniversary-series banner from the title`() {
            // "15 Years GRETCHEN: RYMDEN" → the act "RYMDEN"; artists are unaffected.
            val event = eventWithId("3468")
            event.title shouldBe "RYMDEN"
            event.artists.first() shouldBe ScrapedArtist("Rymden", "HEADLINER")
        }

        @Test
        fun `leaves a non-Gretchen NN-Years title intact`() {
            // The strip is anchored on "Gretchen", so "Recycle: 15 Years FLEXOUT AUDIO" is kept.
            eventWithId("3532").title shouldBe "Recycle: 15 Years FLEXOUT AUDIO"
        }
    }

    @Nested
    inner class EventTypeInference {
        @Test
        fun `defaults a live-music listing to CONCERT`() {
            eventWithId("3492").eventType shouldBe "CONCERT"
        }

        @Test
        fun `types a festival title as FESTIVAL`() {
            eventWithId("3537").eventType shouldBe "FESTIVAL"
        }

        @Test
        fun `types club-night and DJ-set titles as PARTY`() {
            eventWithId("3530").eventType shouldBe "PARTY" // "… CLUB NIGHT"
            eventWithId("3511").eventType shouldBe "PARTY" // "BALKANBEATS - Robert Soko DJ-Set"
        }
    }

    @Nested
    inner class StatusParsing {
        @Test
        fun `maps a cancelled event and strips the status suffix from the title`() {
            val event = eventWithId("3528")

            event.title shouldBe "Deputamadre Club presents: 3BALLMTY"
            event.title shouldNotContain "//"
            event.status shouldBe "CANCELLED"
        }

        @Test
        fun `maps a relocated event and drops the prose relocation note from the lineup`() {
            val event = eventWithId("3420")

            event.title shouldBe "Trinity presents: MUCCO"
            event.status shouldBe "RELOCATED"
            // The .lineup carries a 10-word "verlegt" sentence that must not be minted as an artist.
            event.artists shouldContainExactly listOf(ScrapedArtist("Mucco", "HEADLINER"))
            // The badge and the headline tail travel with the row; the boundary names the destination (#1551).
            event.statusNote shouldBe "neuer Ort  verlegt ins Metropol"
            event.toEventEntity(venueId = 1L, venueSlug = "gretchen", eventSourceId = 1L).relocatedTo shouldBe "Metropol"
        }
    }

    @Test
    fun `every scraped event has the required identity fields`() {
        scrape().forEach { event ->
            event.title.isNotBlank() shouldBe true
            event.sourceId.startsWith("gretchen:") shouldBe true
            event.sourceUrl.startsWith("https://www.gretchen-club.de/detail.php?id=") shouldBe true
        }
    }
}
