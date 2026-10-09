package de.norm.events.scraper.derweissehase

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.splitBackToBack
import de.norm.events.scraper.withWeekdayWarnings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [DerWeisseHaseOverviewPageScraper].
 *
 * Driven by a real snapshot of `/events`, which already covers every shape the parser has to handle:
 * a comma-separated roster and a `<br>`-separated one, a note line written as a `<p>` and one written
 * as an `<h4>`, unbooked slots ("+ Residents", "Surprise DJ", "Contest Winner", "more tba."), an act
 * name containing an `&`, a title repeated across four weeks, two different nights on one date, and
 * the two links that are not ticket URLs. Hand-built documents cover the shapes the live page does
 * not currently show.
 */
class DerWeisseHaseOverviewPageScraperTest {
    private val scraper = DerWeisseHaseOverviewPageScraper()
    private val baseUrl = "https://derweissehase.club/events"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/derweissehase/derweissehase-overview.html")!!
                .bufferedReader()
                .readText()
        events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    /** Wraps the given `.eventrahm` inner markup in the block structure the CMS emits. */
    private fun block(inner: String) =
        Jsoup.parse(
            """<div class="event50"><a href="https://de.ra.co/events/1" class="eventlistlink">
               |<div class="stpic"><img src="/files/flyer.jpg"></div>
               |<div class="eventrahm">$inner</div></a></div>
            """.trimMargin(),
            baseUrl
        )

    @Test
    fun `extracts every announced night in listing order`() {
        events shouldHaveSize 16
        events.first().eventDate shouldBe LocalDate.of(2026, 8, 6)
        events.last().eventDate shouldBe LocalDate.of(2026, 9, 4)
    }

    @Test
    fun `parses every field of a night`() {
        val straff = events.first()
        straff.title shouldBe "straff / thursday techno"
        straff.eventType shouldBe "PARTY"
        straff.eventDate shouldBe LocalDate.of(2026, 8, 6)
        straff.startTime shouldBe LocalTime.of(23, 0)
        straff.sourceUrl shouldBe baseUrl
        straff.sourceId shouldBe "der_weisse_hase:2026-08-06-straff-thursday-techno"
        straff.imageUrl shouldBe "https://derweissehase.club/files/Bilder/EVENTS/2608/0608.jpg"
        straff.ticketUrl shouldBe "https://de.ra.co/events/2493901"
        straff.status shouldBe "SCHEDULED"
        straff.free shouldBe false
        // The club publishes none of these anywhere on the page.
        straff.description.shouldBeNull()
        straff.doorsTime.shouldBeNull()
        straff.genre.shouldBeNull()
        straff.pricePresale.shouldBeNull()
        straff.priceBoxOffice.shouldBeNull()
        // "+ Surprise DJ" closes the billing and is not a performer.
        straff.artists shouldContainExactly
            listOf(
                ScrapedArtist("Fran-Cee", "DJ"),
                ScrapedArtist("Fabian Fischbach", "DJ"),
                ScrapedArtist("DAV3", "DJ")
            )
    }

    @Test
    fun `keeps an act name that contains an ampersand whole`() {
        val liebeUndBass = events.single { it.eventDate == LocalDate.of(2026, 8, 15) }
        liebeUndBass.artists.map { it.name } shouldContainExactly
            listOf(
                "Jam El Mar",
                "Drauf & Dran DJ Team",
                "Maschine",
                "M.R.C.",
                "PUK",
                "NIKO INCRAVALLE",
                "Bisk",
                "Swaytone",
                "Sika Akis",
                "Blue Sky",
                "Rafa-fx",
                "DAZA"
            )
    }

    @Test
    fun `reads a line-break separated roster and drops the residents slot`() {
        val tuesdayRave = events.single { it.eventDate == LocalDate.of(2026, 8, 11) }
        tuesdayRave.artists shouldContainExactly
            listOf(
                ScrapedArtist("Cat Vermillion", "DJ"),
                ScrapedArtist("TechNovaBader", "DJ")
            )
    }

    @Test
    fun `stores an entry note as the price note without flagging the night free`() {
        val tuesdayRave = events.single { it.eventDate == LocalDate.of(2026, 8, 11) }
        tuesdayRave.priceNote shouldBe "free entry until midnight*"
        tuesdayRave.description.shouldBeNull()
        tuesdayRave.free shouldBe false
        tuesdayRave.toEventEntity(venueId = 1L, venueSlug = "der-weisse-hase", eventSourceId = 1L).free shouldBe false
    }

    @Test
    fun `stores an entry note written as a heading as the price note too`() {
        val femAll = events.single { it.eventDate == LocalDate.of(2026, 8, 21) }
        femAll.priceNote shouldBe "Women & FLINTA free until 1 AM"
        femAll.artists.first() shouldBe ScrapedArtist("Nat SuPrise", "DJ")
    }

    @Test
    fun `drops the more-to-come and contest-winner placeholders`() {
        val socialRave = events.single { it.title == "ZUG DER LIEBE ° SOCIAL RAVE" }
        socialRave.artists.map { it.name } shouldContainExactly
            listOf("Maschine", "PUK", "M.R.C.", "NIKO INCRAVALLE", "Kaminka Merel", "Bonq")

        val antrieb = events.single { it.eventDate == LocalDate.of(2026, 9, 4) }
        antrieb.artists.map { it.name }.last() shouldBe "Simple"
    }

    // Production stored `Jens Schwan b2b Simon Berlin` and `B2B Alina Matini` as one act each (#2966).
    // The scraper hands each entry on whole; the import boundary splits it.
    @Test
    fun `a back-to-back slot and a b2b that opens an entry become single DJs at the boundary`() {
        val night =
            block(
                """<p class="dater">Samstag 10.10.2026 23:00</p><h1>Klubnacht</h1><h4>LINE UP</h4>
                   |<p>Funk@delic, Jens Schwan b2b Simon Berlin, Buda (Paris), Kalimanda, B2B Alina Matini, Drauf &amp; Dran DJ Team</p>
                """.trimMargin()
            )

        scraper
            .scrape(night, baseUrl)
            .single()
            .artists
            .flatMap { splitBackToBack(it.name) } shouldContainExactly
            listOf("Funk@delic", "Jens Schwan", "Simon Berlin", "Buda (Paris)", "Kalimanda", "Alina Matini", "Drauf & Dran DJ Team")
    }

    // Production stored the 30 Oct night with no act: its roster heading read `Musikanten:` (#2982).
    @Test
    fun `reads a roster under the Musikanten heading`() {
        val night =
            block(
                """<p class="dater">Freitag 30.10.2026 23:00</p><h1>Techno Ihr Hasen / Detroit Connection</h1><p>&nbsp;</p>
                   |<p class="text"><h4>Musikanten:</h4>
                   |<p>Jason Garcia b2b T.Linder, Irving Vasquez Ixbalanke, Emanuel Eisbrenner , DAV3, Blue Sky, DAZA + Surprise Act</p></p>
                """.trimMargin()
            )

        val parsed = scraper.scrape(night, baseUrl).single()
        parsed.artists.map { it.name } shouldContainExactly
            listOf("Jason Garcia b2b T.Linder", "Irving Vasquez Ixbalanke", "Emanuel Eisbrenner", "DAV3", "Blue Sky", "DAZA")
        parsed.description.shouldBeNull()
        parsed.priceNote.shouldBeNull()
    }

    @Test
    fun `distinguishes a recurring night by its date`() {
        val straff = events.filter { it.title == "straff / thursday techno" }
        straff shouldHaveSize 4
        straff.map { it.sourceId } shouldContainExactly
            listOf(
                "der_weisse_hase:2026-08-06-straff-thursday-techno",
                "der_weisse_hase:2026-08-13-straff-thursday-techno",
                "der_weisse_hase:2026-08-20-straff-thursday-techno",
                "der_weisse_hase:2026-08-27-straff-thursday-techno"
            )
    }

    @Test
    fun `distinguishes two nights that share a date by their title`() {
        val zugDerLiebe = events.filter { it.eventDate == LocalDate.of(2026, 8, 29) }
        zugDerLiebe shouldHaveSize 2
        zugDerLiebe.map { it.startTime } shouldContainExactly listOf(LocalTime.of(12, 0), LocalTime.of(22, 0))
        zugDerLiebe.map { it.sourceId } shouldContainExactly
            listOf(
                "der_weisse_hase:2026-08-29-zug-der-liebe-social-rave",
                "der_weisse_hase:2026-08-29-zug-der-liebe-demo-aftershow"
            )
    }

    @Test
    fun `ignores a link that is not a Resident Advisor event page`() {
        // The club's RA profile, used for a night whose event page is not up yet.
        events.single { it.eventDate == LocalDate.of(2026, 8, 20) }.ticketUrl.shouldBeNull()
        // A bare "#" back to the listing, used the same way.
        events.single { it.title == "ZUG DER LIEBE ° SOCIAL RAVE" }.ticketUrl.shouldBeNull()
    }

    @Test
    fun `returns no artists for a night announced without a roster`() {
        val parsed = scraper.scrape(block("""<p class="dater">Freitag 04.09.2026 23:00</p><h1>Techno Ihr Hasen</h1>"""), baseUrl)
        parsed shouldHaveSize 1
        parsed[0].artists.shouldBeEmpty()
        parsed[0].description.shouldBeNull()
    }

    @Test
    fun `accepts a date line without a time`() {
        val parsed = scraper.scrape(block("""<p class="dater">Freitag 04.09.2026</p><h1>Techno Ihr Hasen</h1>"""), baseUrl)
        parsed shouldHaveSize 1
        parsed[0].eventDate shouldBe LocalDate.of(2026, 9, 4)
        parsed[0].startTime.shouldBeNull()
    }

    @Test
    fun `skips a block with an unparseable date`() {
        scraper.scrape(block("""<p class="dater">demnächst</p><h1>Techno Ihr Hasen</h1>"""), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `skips a block with no title`() {
        scraper.scrape(block("""<p class="dater">Freitag 04.09.2026 23:00</p>"""), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `returns no events for a page without a programme`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `moves a night whose weekday names the neighbouring month`() {
        // 3 September 2026 is a Thursday; the Saturday the heading names is 3 October.
        val (events, warnings) =
            withWeekdayWarnings { scraper.scrape(block("""<p class="dater">Samstag 03.09.2026 23:00</p><h1>Klubnacht</h1>"""), baseUrl) }

        events.single().eventDate shouldBe LocalDate.of(2026, 10, 3)
        events.single().sourceId shouldBe "der_weisse_hase:2026-10-03-klubnacht"
        warnings shouldHaveSize 1
    }
}
