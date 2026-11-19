package de.norm.events.scraper.peteredel

import de.norm.events.event.ArtistRole
import de.norm.events.promoter.splitCoBilledPromoter
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.forChildrenPhrase
import de.norm.events.scraper.isForChildren
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [PeterEdelOverviewPageScraper].
 *
 * Driven by a real snapshot of `/events/`, which covers the shapes that matter: seven month headings
 * supplying the year for their year-less rows (including two that roll into the next year), separate
 * and collapsed doors/start labels, a transposed pair, from-prices and TBA box-office lines, a
 * sold-out event, two cancelled ones, support billings, and a middle column whose `Tickets:` line
 * holds a sale-start date rather than a price. Hand-built documents cover what the live page does not
 * currently show.
 *
 * The snapshot opens with a month heading, which the live page stopped doing once August wound down
 * (#498). That shape is pinned by hand-built documents rather than a second snapshot: the page swaps
 * between the two every month, so a capture would only record whichever side of the switch it was
 * taken on, while these state the rule.
 */
class PeterEdelOverviewPageScraperTest {
    private val scraper = PeterEdelOverviewPageScraper()
    private val baseUrl = "https://www.peteredel.de/events/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        events = scraper.scrape(fixtureDocument("peteredel/peteredel-overview.html", baseUrl), baseUrl)
    }

    /** One event box's markup — the unit both grid builders compose. */
    private fun box(
        dateLine: String,
        main: String,
        ticketColumn: String = "<h3>Tickets: 10,00€</h3>"
    ) = """<div class="box-rc-dark-grey"><div class="container"><div class="row clearfix">
           |<div class="col-md-2 column"><div><div><h3>$dateLine</h3></div><p><img src="/media/1/f.jpg"></p></div></div>
           |<div class="col-md-8 column"><div>$main</div></div>
           |<div class="col-md-2 column"><div>$ticketColumn</div></div>
           |</div></div></div>
        """.trimMargin()

    /** Builds a grid holding one month heading and one event box with the given main-column markup. */
    private fun grid(
        heading: String?,
        dateLine: String,
        main: String,
        ticketColumn: String = "<h3>Tickets: 10,00€</h3>"
    ) = Jsoup.parse(
        """<div class="grid-section">
           |${heading?.let { """<div><h1>$it</h1></div>""" }.orEmpty()}
           |${box(dateLine, main, ticketColumn)}
           |</div>
        """.trimMargin(),
        baseUrl
    )

    /**
     * Builds the shape the live page took on 2026-08-14 (#498): an event box above the first month
     * heading, and one below it. Each box is titled after its date line so the two stay tellable
     * apart.
     */
    private fun gridWithLeadingBox(
        leadingDateLine: String,
        heading: String,
        followingDateLine: String
    ) = Jsoup.parse(
        """<div class="grid-section">
           |${box(leadingDateLine, "<h3>Above</h3>")}
           |<div><h1>$heading</h1></div>
           |${box(followingDateLine, "<h3>Below</h3>")}
           |</div>
        """.trimMargin(),
        baseUrl
    )

    @Test
    fun `extracts every event in listing order`() {
        // 39 boxes, three of them children's shows (#3796).
        events shouldHaveSize 39
        events.first().eventDate shouldBe LocalDate.of(2026, 8, 20)
        events.last().eventDate shouldBe LocalDate.of(2027, 5, 22)
    }

    @Test
    fun `parses every field of a concert`() {
        val polica = events.single { it.title.startsWith("Poliça") }
        polica.title shouldBe "Poliça - Dreams Go Tour 2026"
        polica.subtitle shouldBe "Support: Sian Able"
        polica.eventDate shouldBe LocalDate.of(2026, 8, 25)
        polica.doorsTime shouldBe LocalTime.of(19, 0)
        polica.startTime shouldBe LocalTime.of(20, 0)
        polica.sourceUrl shouldBe baseUrl
        polica.sourceId shouldBe "peter_edel:2026-08-25-polica-dreams-go-tour-2026"
        polica.ticketUrl shouldBe "https://tickets.loft.de/event/polica-92q9t2"
        polica.imageUrl shouldBe "https://www.peteredel.de/media/2598/polica.jpg"
        polica.pricePresale shouldBe BigDecimal("25.00")
        polica.priceBoxOffice shouldBe BigDecimal("32")
        polica.priceNote shouldBe "Tickets: 25,00€ (zzgl. VVK-Gebühr) Abendkasse: 32 Euro"
        polica.status shouldBe "SCHEDULED"
        polica.soldOut shouldBe false
        polica.free shouldBe false
        polica.promoters shouldContainExactly listOf("Loft Concerts")
        polica.description!! shouldContain "die beste Band aller Zeiten"
        // The venue states no category, but a support billing makes the night a concert.
        polica.eventType shouldBe "CONCERT"
        polica.artists shouldContainExactly
            listOf(
                ScrapedArtist("Poliça", ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist("Sian Able", ArtistRole.SUPPORT)
            )
    }

    @Test
    fun `takes the year from the month heading above each row`() {
        // The rows carry no year at all; only the headings do, and the programme crosses into 2027.
        events.single { it.title == "ERIKA VIKMAN" }.eventDate shouldBe LocalDate.of(2027, 3, 8)
        events.single { it.title == "MAMA LEISA" }.eventDate shouldBe LocalDate.of(2027, 5, 22)
        events.filter { it.title == "Tanztee im PETER EDEL" }.map { it.eventDate } shouldContainExactly
            listOf(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1))
    }

    @Test
    fun `reads a collapsed doors and start label as one time`() {
        val afterWork = events.first { it.title.startsWith("AFTER WORK") }
        // "Beginn & Einlass: 18:00 Uhr" — one time serving as both.
        afterWork.doorsTime shouldBe LocalTime.of(18, 0)
        afterWork.startTime shouldBe LocalTime.of(18, 0)
        afterWork.eventType shouldBe "PARTY"
    }

    @Test
    fun `keeps a transposed doors and start pair as written for the persistence boundary to fix`() {
        // The venue wrote "Einlass: 15:00 Uhr Beginn: 14:00 Uhr"; orderDoorsBeforeStart swaps it on save.
        val carmenMaja = events.single { it.title.startsWith("Carmen Maja") }
        carmenMaja.doorsTime shouldBe LocalTime.of(15, 0)
        carmenMaja.startTime shouldBe LocalTime.of(14, 0)
    }

    @Test
    fun `drops a night for invited guests only`() {
        // Event 14535, a club's anniversary party.
        val document =
            grid(
                heading = "November 2026",
                dateLine = "FR | 20.11.",
                main =
                    "<h3><strong>25 JAHRE MAGNET CLUB</strong></h3><p><strong>JUBILÄUMSFEIER</strong></p>" +
                        "<p>Die Veranstaltung ist nur für geladene Gäste.<br><strong>Einlass &amp; Beginn: </strong>18:00 Uhr</p>",
                ticketColumn = "<h3>Tickets:<br><br>Nur für<br>geladene Gäste</h3>"
            )

        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }

    @Test
    fun `marks a sold-out event and stores no price for it`() {
        val carmenMaja = events.single { it.title.startsWith("Carmen Maja") }
        carmenMaja.soldOut shouldBe true
        carmenMaja.pricePresale.shouldBeNull()
        carmenMaja.priceBoxOffice.shouldBeNull()
        carmenMaja.promoters shouldContainExactly listOf("Volkssolidarität Berlin")
    }

    @Test
    fun `strips the cancelled marker from the title and sets the status`() {
        // The fixture's two cancelled boxes are children's shows, which are dropped, so the shape is rebuilt here.
        val cancelled =
            scraper
                .scrape(
                    grid("Oktober 2026", "SO | 04.10.", "<h3><strong>Talkshow: Däumelinchen [Abgesagt!]</strong></h3>", "<h3>Leider abgesagt!</h3>"),
                    baseUrl
                ).single()
        cancelled.status shouldBe "CANCELLED"
        cancelled.title shouldBe "Talkshow: Däumelinchen"
        // The marker is out of the identity too, so reinstating the show keeps the same row.
        cancelled.sourceId shouldBe "peter_edel:2026-10-04-talkshow-daumelinchen"
        // "Leider abgesagt!" is a status, not a pricing note.
        cancelled.priceNote.shouldBeNull()
    }

    @Test
    fun `marks the date a show left as postponed and strips the marker`() {
        // Event 13421: the venue keeps the 22.11. box beside the new 29.11. one (event 13422).
        val main =
            "<h3>SONNTAGSSCHÖN [Achtung: Terminverschiebung!]</h3><p>Terminverschiebung auf 29.11.2026! Indie &amp; Wave Tanzcafé - mit DJ Iron Henning</p>" +
                "<div class=\"text-block\"><div class=\"text-container\">Es ist wieder soweit!</div></div><p>Beginn &amp; Einlass: 16:00 Uhr</p>"
        val left = scraper.scrape(grid("November 2026", "SO | 22.11.", main), baseUrl).single()
        left.status shouldBe "POSTPONED"
        left.title shouldBe "SONNTAGSSCHÖN"
        left.sourceId shouldBe "peter_edel:2026-11-22-sonntagsschon"
        val entity = left.toEventEntity(venueId = 1, venueSlug = "kulturhaus-peter-edel", eventSourceId = 1)
        entity.status shouldBe "POSTPONED"
        entity.eventType shouldBe "PARTY"
    }

    @Test
    fun `reads the styles a dance café names`() {
        // Events 13421 and 13422.
        val main =
            "<h3>SONNTAGSSCHÖN</h3><p>Terminverschiebung vom 22.11.2026 auf den 29.11.2026! Indie &amp; Wave Tanzcafé - mit DJ Iron Henning</p>" +
                "<div class=\"text-block\"><div class=\"text-container\">Es ist wieder soweit!</div></div><p>Beginn &amp; Einlass: 16:00 Uhr</p>"
        val event = scraper.scrape(grid("November 2026", "SO | 29.11.", main), baseUrl).single()
        event.status shouldBe "SCHEDULED"
        event.eventType shouldBe "PARTY"
        event.genre shouldBe "Indie, Wave"
    }

    @Test
    fun `marks the children's shows`() {
        events.filter { it.isForChildren }.map { it.forChildrenPhrase } shouldBe listOf("Kindertheater", "Kinder-Halloween", "Kindertheater")
    }

    @Test
    fun `reads prices from the ticket column rather than the prose`() {
        // The middle column reads "Tickets: Ab 17.04.26 - online … erhältlich!" — a sale-start date
        // that would parse as a number; the ticket column carries the real price.
        val emmaHarner = events.single { it.title == "Emma Harner" }
        emmaHarner.pricePresale shouldBe BigDecimal("25.00")
        emmaHarner.priceBoxOffice shouldBe BigDecimal("30")
        emmaHarner.subtitle shouldBe "\"The Evening Star\"-Tour"
    }

    @Test
    fun `keeps a from-price qualifier and a tier list in the price note`() {
        val nosferatu = events.single { it.title.startsWith("NOSFERATU") }
        nosferatu.pricePresale shouldBe BigDecimal("25.00")
        nosferatu.priceBoxOffice shouldBe BigDecimal("28")
        nosferatu.priceNote shouldBe "Tickets: Ab 25,00€ (inkl. VVK-Gebühr) Abendkasse Ab 28 Euro"
        nosferatu.eventType shouldBe "SCREENING"

        val talkshow = events.single { it.title.startsWith("Talkshow") }
        talkshow.priceBoxOffice shouldBe BigDecimal("30")
        talkshow.priceNote shouldBe "Tickets: Ab 27,00€ (inkl. VVK-Gebühr) Abendkasse 30 Euro / 35 Euro"
        talkshow.eventType shouldBe "SHOW"
    }

    @Test
    fun `stores no box-office price for a TBA line`() {
        val timonKrause = events.single { it.title == "Timon Krause" }
        timonKrause.pricePresale shouldBe BigDecimal("29.90")
        timonKrause.priceBoxOffice.shouldBeNull()
        timonKrause.priceNote shouldBe "Tickets: 29,90€ (inkl. VVK-Gebühr) Abendkasse TBA"
    }

    @Test
    fun `reads the Tageskasse line as the box-office price`() {
        val tanztee = events.first { it.title == "Tanztee im PETER EDEL" }
        tanztee.pricePresale shouldBe BigDecimal("11.34")
        tanztee.priceBoxOffice shouldBe BigDecimal("11.34")
        tanztee.eventType shouldBe "PARTY"
    }

    @Test
    fun `takes artists only where the venue bills a support act or a tour, or the title names FIL`() {
        val withArtists = events.filter { it.artists.isNotEmpty() }
        withArtists.map { it.title } shouldContainExactly
            listOf(
                "Poliça - Dreams Go Tour 2026",
                "Buffalo Tom",
                "FIL'S TESTSHOWS",
                "FIL'S TESTSHOWS",
                "Veljanov - Shanderilan Tour",
                "Monsters of Liedermaching - Absolute Aufnahmetalente",
                "FIL",
                "FIL"
            )
        withArtists.drop(4).take(2).map { event -> event.artists.map { it.name } } shouldContainExactly
            listOf(listOf("Veljanov"), listOf("Monsters of Liedermaching"))
        withArtists[1].artists shouldContainExactly
            listOf(
                ScrapedArtist("Buffalo Tom", ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist("Grateful Cat", ArtistRole.SUPPORT)
            )
    }

    @Test
    fun `recovers a reading from the subtitle`() {
        events.single { it.title.startsWith("Mikis Wesensbitter") }.eventType shouldBe "READING"
    }

    @Test
    fun `never flags a paid event as free`() {
        events.none { it.free } shouldBe true
    }

    @Test
    fun `dates a box above the first month heading from the heading below it`() {
        // The venue retires the current month's heading while its remaining dates stay listed (#498).
        val parsed = scraper.scrape(gridWithLeadingBox("DO | 20.08.", "SEPTEMBER 2026", "DI | 15.09."), baseUrl)
        parsed.map { it.title } shouldContainExactly listOf("Above", "Below")
        parsed.map { it.eventDate } shouldContainExactly listOf(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 15))
    }

    @Test
    fun `puts a box above a January heading in the year before it`() {
        // The listing runs chronologically, so a December row above JANUAR 2027 is 2026 — not 2027.
        val parsed = scraper.scrape(gridWithLeadingBox("DI | 29.12.", "JANUAR 2027", "FR | 08.01."), baseUrl)
        parsed.map { it.eventDate } shouldContainExactly listOf(LocalDate.of(2026, 12, 29), LocalDate.of(2027, 1, 8))
    }

    @Test
    fun `keeps a box above a heading whose own month matches it in that heading's year`() {
        // A row on the heading's own month is not a wrap — MÄRZ exercises the one month German does not abbreviate.
        val parsed = scraper.scrape(gridWithLeadingBox("MO | 02.03.", "MÄRZ 2027", "MO | 08.03."), baseUrl)
        parsed.map { it.eventDate } shouldContainExactly listOf(LocalDate.of(2027, 3, 2), LocalDate.of(2027, 3, 8))
    }

    @Test
    fun `skips a box above a heading whose month does not parse, and keeps the one below`() {
        // The year alone cannot place the row, and guessing is worse than the omission.
        val parsed = scraper.scrape(gridWithLeadingBox("DO | 20.08.", "SOMMER 2026", "DI | 15.09."), baseUrl)
        parsed.map { it.title } shouldContainExactly listOf("Below")
        parsed.single().eventDate shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `skips a box that has no month heading anywhere below it`() {
        scraper.scrape(grid(heading = null, dateLine = "DO | 20.08.", main = "<h3>Something</h3>"), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `skips a box with an unparseable date line`() {
        scraper.scrape(grid("AUGUST 2026", "demnächst", "<h3>Something</h3>"), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `skips a box with an impossible date`() {
        scraper.scrape(grid("AUGUST 2026", "DO | 31.02.", "<h3>Something</h3>"), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `skips a box with no title`() {
        scraper.scrape(grid("AUGUST 2026", "DO | 20.08.", "<p>Einlass: 19:00 Uhr</p>"), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `stops the promoter credit at a Hinweis note`() {
        val main =
            "<h3>Rudelsingen</h3><div class=\"text-block\"><p>Mitsingen.</p></div>" +
                "<p>Einlass: 18:00 Uhr | Beginn: 19:00 Uhr<br/><strong>Präsentiert von:</strong> " +
                "Rudelsingen - Das Original aus Münster Hinweis: Diese Veranstaltung ist Teil des Kultursommers!</p>"
        val parsed = scraper.scrape(grid("AUGUST 2026", "DO | 20.08.", main), baseUrl)
        parsed[0].promoters shouldContainExactly listOf("Rudelsingen - Das Original aus Münster")
    }

    @Test
    fun `splits a comma credit and drops the zero-width space before its comma`() {
        // Wolf Parade (event 13807) on the live page of 2026-11-19 (#5275).
        val main =
            "<h3>Wolf Parade</h3><p>20 Jahre \"Apologies to the Queen Mary\"</p>" +
                "<div class=\"text-block\"><p>Indie-Rock aus Montréal.</p></div>" +
                "<p>Einlass: 19:00 Uhr | Beginn: 20:00 Uhr<br/><strong>Präsentiert von:</strong> " +
                "<a href=\"https://loft.de/\">LOFT CONCERTS</a>\u200B, FluxFM &amp; EINEN HAB ICH NOCH</p>"
        val promoters = scraper.scrape(grid("JUNI 2027", "SA | 05.06.", main), baseUrl).single().promoters

        promoters shouldContainExactly listOf("LOFT CONCERTS", "FluxFM & EINEN HAB ICH NOCH")
        // The import splits the spaced `&` because FluxFM is a stored promoter.
        promoters.flatMap { raw -> splitCoBilledPromoter(raw) { it == "fluxfm" } } shouldContainExactly
            listOf("LOFT CONCERTS", "FluxFM", "EINEN HAB ICH NOCH")
    }

    @Test
    fun `keeps a show credited with a spaced ampersand whole`() {
        val parsed = scraper.scrape(fixtureDocument("peteredel/peteredel-blurbs-2026-10.html", baseUrl), baseUrl)
        parsed.single { it.title == "Frack & Spitzenhöschen" }.promoters shouldContainExactly listOf("Frack & Spitzenhöschen")
    }

    @Test
    fun `accepts an event announced without times, prices or a subtitle`() {
        val parsed = scraper.scrape(grid("AUGUST 2026", "DO | 20.08.", "<h3>Something</h3>", ticketColumn = ""), baseUrl)
        parsed shouldHaveSize 1
        parsed[0].title shouldBe "Something"
        parsed[0].subtitle.shouldBeNull()
        parsed[0].doorsTime.shouldBeNull()
        parsed[0].startTime.shouldBeNull()
        parsed[0].pricePresale.shouldBeNull()
        parsed[0].priceNote.shouldBeNull()
        parsed[0].promoters.shouldBeEmpty()
        parsed[0].artists.shouldBeEmpty()
        parsed[0].eventType shouldBe "OTHER"
        parsed[0].typeIsFallback shouldBe true
    }

    @Test
    fun `a typed night is no fallback`() {
        val main = "<h3>FIL'S TESTSHOWS</h3><p>Rohe neue Scherze und unfertige Songs</p>"
        scraper.scrape(grid("OKTOBER 2026", "FR | 16.10.", main), baseUrl).single().typeIsFallback shouldBe false
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = [
            "FIL'S TESTSHOWS | Rohe neue Scherze und unfertige Songs | probiert Fil neue Witze und Lieder aus. | COMEDY",
            "DEFA-Filmküche - Klubgespräch im PETER EDEL | Paul Werner Wagner im Gespräch mit Knut Elstermann | Der Moderator. | READING",
            "Rudelsingen - Das Original aus Münster | | Wir singen gemeinsam die größten Hits. | CONCERT",
            "Klavierfest Berlin-Weißensee 2027 - Liederabend | Zwischen Wort und Klang | Lieder nach einem Buch. | CONCERT",
            "Miriam Davoudvandi | Das können wir uns nicht leisten - Tour 2026 | Mit ihrem Buch bringt sie ihre Geschichte auf die Bühne. | READING",
            "VAMPIRES BALL | Nina Queers Halloween | Aus ihrer legendären Halloween-Party wird ein Ball. | PARTY",
            "Sip & Smash X Sublime | | Das PETER EDEL wird zum sportlichsten Dancefloor der Stadt. | PARTY",
            "Veljanov - Shanderilan Tour | | Mit seinen drei Studioalben zeigt der Frontmann seine Stimme. | CONCERT",
            "Neu-Zeit: Dr. Neu trifft Michael Müller | Berlin hat gewählt - wohin geht die Stadt? | Leider abgesagt. | OTHER"
        ]
    )
    fun `types a night from the house's own words in the title, the subtitle or the blurb`(
        title: String,
        subtitle: String?,
        blurb: String,
        type: String
    ) {
        val main =
            "<h3>$title</h3>${subtitle?.let { "<p>$it</p>" }.orEmpty()}" +
                "<div class=\"text-block\"><div class=\"text-container\"><p>$blurb</p></div></div>"
        scraper.scrape(grid("OKTOBER 2026", "FR | 16.10.", main), baseUrl).single().eventType shouldBe type
    }

    /**
     * Seven boxes cut from the live page of 2026-10-15 (#3324), headings kept: four blurbs that name
     * a podcast, a Varieté show, concerts or an orchestra's duets, and three that name none.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "2027-10-12 | Schwarze Akte | READING",
            "2026-11-14 | Frack & Spitzenhöschen | SHOW",
            "2027-03-08 | ERIKA VIKMAN | CONCERT",
            "2027-04-03 | Sinatra & The Swingin' Ladies | CONCERT",
            "2026-12-12 | FIL | OTHER",
            "2027-05-22 | MAMA LEISA | OTHER",
            "2026-11-01 | Tanztee im PETER EDEL | PARTY"
        ]
    )
    fun `types a night from the podcast, revue and concert words in its blurb`(
        date: LocalDate,
        title: String,
        type: String
    ) {
        val parsed = scraper.scrape(fixtureDocument("peteredel/peteredel-blurbs-2026-10.html", baseUrl), baseUrl)
        parsed.single { it.eventDate == date && it.title == title }.eventType shouldBe type
    }

    /** Two boxes cut from the live page of 2026-10-21: FIL's extra show of 2027-02-20, and the night that points to it. */
    private fun extraShowEvents(): List<ScrapedEvent> = scraper.scrape(fixtureDocument("peteredel/peteredel-extra-show-2026-10.html", baseUrl), baseUrl)

    @Test
    fun `bills the act before a quoted show and types the comedian's night`() {
        val event = extraShowEvents().single { it.eventDate == LocalDate.of(2027, 2, 20) }
        event.title shouldBe "FIL - \"Lieder of the world\""
        event.artists shouldContainExactly listOf(ScrapedArtist("FIL", ArtistRole.HEADLINER, titleDerived = true))
        // "Als erster Komiker der Welt": the blurb names no joke, but it names a comedian.
        event.eventType shouldBe "COMEDY"
    }

    @ParameterizedTest
    @ValueSource(strings = ["FIL - MIT NEUER SHOW", "FIL'S TESTSHOWS"])
    fun `bills FIL off a title that only names him`(title: String) {
        scraper
            .scrape(grid("DEZEMBER 2026", "SA | 12.12.", "<h3>$title</h3>"), baseUrl)
            .single()
            .artists shouldContainExactly listOf(ScrapedArtist("FIL", ArtistRole.HEADLINER, titleDerived = true))
    }

    @Test
    fun `keeps an extra-show line out of the subtitle`() {
        val events = extraShowEvents()
        events.map { it.subtitle } shouldContainExactly listOf(null, null)
        events.map { it.statusNote } shouldContainExactly
            listOf("Zusatzshow am 20.02.2027 - Tickets jetzt erhältlich!", "ZUSATZSHOW!")
        events.map { it.status } shouldContainExactly listOf("SCHEDULED", "SCHEDULED")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // One quoted word can finish the act's own name, so it confirms no act.
            "KOSMOS - \"Lieder\"",
            // A party's title names the night, quoted show or not.
            "Tanztee im PETER EDEL - \"Die goldenen Zwanziger\""
        ]
    )
    fun `bills no act where the quoted show is one word or the night is a party`(title: String) {
        scraper
            .scrape(grid("OKTOBER 2026", "FR | 16.10.", "<h3>$title</h3>"), baseUrl)
            .single()
            .artists
            .shouldBeEmpty()
    }

    /** One concert box with the given title and subtitle, typed by a blurb that names a concert. */
    private fun concertActs(
        title: String,
        subtitle: String?
    ): List<String> {
        val main =
            "<h3>$title</h3>${subtitle?.let { "<p>$it</p>" }.orEmpty()}" +
                "<div class=\"text-block\"><div class=\"text-container\"><p>Ein Konzert.</p></div></div>"
        val event = scraper.scrape(grid("DEZEMBER 2026", "SO | 20.12.", main), baseUrl).single()
        event.eventType shouldBe "CONCERT"
        event.artists.forEach { it shouldBe ScrapedArtist(it.name, ArtistRole.HEADLINER, titleDerived = true) }
        return event.artists.map { it.name }
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '\'',
        value = [
            // A quoted work in the subtitle: event 13807.
            "Wolf Parade | 20 Jahre \"Apologies to the Queen Mary\" | Wolf Parade",
            "Wolf Parade | Das Album „Apologies to the Queen Mary“ live | Wolf Parade",
            // A dash tail that ends in a tour: event 2010.
            "Veljanov - Shanderilan Tour | | Veljanov",
            // A tour inside a word of the subtitle: event 2013.
            "Monsters of Liedermaching - Absolute Aufnahmetalente | Die Aufnahmetour - Live in Berlin | Monsters of Liedermaching",
            // A tour subtitle under a title with no dash bills the whole title.
            "Wolf Parade | Europe Tour 2027 | Wolf Parade",
            // A closing `- mit <name>`: event 10095.
            "Klassische Adventsmatinée - mit Yannick Rafalimanana | Vorfestliche Klänge mit Flügel und Violine | Yannick Rafalimanana"
        ]
    )
    fun `bills a concert's act from a tour, a quoted work in the subtitle or a closing mit`(
        title: String,
        subtitle: String?,
        act: String
    ) {
        concertActs(title, subtitle) shouldContainExactly listOf(act)
    }

    @Test
    fun `bills every name a closing mit lists`() {
        concertActs("Adventsmatinée - mit Yannick Rafalimanana und Eri Mantani", null) shouldContainExactly
            listOf("Yannick Rafalimanana", "Eri Mantani")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            // A format, not an act: no tour, no quoted work, no closing mit.
            "Rudelsingen - Das Original aus Münster | ",
            // A possessive first name is not a billing (event 12748).
            "Yannicks Salon - Meisterwerke der Kammermusik | Mit Yannick Rafalimanana",
            // A head of more than five words names a programme.
            "Die schönsten Lieder der Zwanziger und Dreißiger Jahre | Die Jubiläumstour 2027"
        ]
    )
    fun `bills no act for a concert whose title only names its format`(
        title: String,
        subtitle: String?
    ) {
        concertActs(title, subtitle).shouldBeEmpty()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            // A party's title names the night, mit or not.
            "Indie & Wave Tanzcafé - mit DJ Iron Henning | | PARTY",
            // A tour confirms an act only for a concert.
            "Miriam Davoudvandi | Das können wir uns nicht leisten - Tour 2026 | READING"
        ]
    )
    fun `bills no act from a tour or a closing mit outside a concert`(
        title: String,
        subtitle: String?,
        type: String
    ) {
        val main =
            "<h3>$title</h3>${subtitle?.let { "<p>$it</p>" }.orEmpty()}" +
                "<div class=\"text-block\"><div class=\"text-container\"><p>Mit ihrem Buch auf der Bühne.</p></div></div>"
        val event = scraper.scrape(grid("DEZEMBER 2026", "SO | 20.12.", main), baseUrl).single()
        event.eventType shouldBe type
        event.artists.shouldBeEmpty()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            // A revue blurb that names a concert is still a show: the revue cue comes first.
            "Ein Abend aus Varieté und Konzert. | SHOW",
            "Das Musical mit Live-Orchester. | SHOW",
            // An orchestra, in German or English, plays a concert.
            "Das Landesjugendorchester spielt. | CONCERT",
            "Die schönsten Duette der Filmgeschichte. | CONCERT",
            // "show" alone decides nothing: comedians and singers both play live shows.
            "Seine neue Show, live erlebbar. | OTHER",
            // A podcast taped live is a reading, as #2992 decided.
            "Der Podcast kommt auf die Bühne. | READING"
        ]
    )
    fun `orders the blurb cues so a revue wins over a concert and show alone is no cue`(
        blurb: String,
        type: String
    ) {
        val main = "<h3>Something</h3><div class=\"text-block\"><div class=\"text-container\"><p>$blurb</p></div></div>"
        scraper.scrape(grid("OKTOBER 2026", "FR | 16.10.", main), baseUrl).single().eventType shouldBe type
    }

    @Test
    fun `returns no events for a page without a programme grid`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
