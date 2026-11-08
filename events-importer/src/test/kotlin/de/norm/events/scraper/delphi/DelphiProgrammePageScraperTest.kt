package de.norm.events.scraper.delphi

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.fixtureText
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [DelphiProgrammePageScraper].
 *
 * Parses a static snapshot of the Theater im Delphi `/programm/` page for deterministic,
 * offline-safe testing without HTTP fetching. The fixture keeps the page's leaked record dump
 * alongside the rendered table, so the price join is exercised on real data.
 */
class DelphiProgrammePageScraperTest {
    private val scraper = DelphiProgrammePageScraper()
    private val baseUrl = "https://theater-im-delphi.de/programm/"

    private val events: List<ScrapedEvent> by lazy {
        scraper.scrape(fixtureDocument("delphi/delphi-programm.html", baseUrl), baseUrl)
    }

    private val credits: List<ScrapedEvent> by lazy {
        scraper.scrape(fixtureDocument("delphi/delphi-programm-credits.html", baseUrl), baseUrl)
    }

    private val variete: List<ScrapedEvent> by lazy {
        scraper.scrape(fixtureDocument("delphi/delphi-programm-variete.html", baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    /** One row on a one-month page, with the markup the venue renders. An empty [label] leaves the cell empty, as on the page. */
    private fun scrapedRow(
        title: String,
        teaser: String,
        label: String = ""
    ): ScrapedEvent {
        val labelCell = if (label.isEmpty()) "" else "$label<br /> <br />"
        val html =
            """
            <h2 class="month">März 2027</h2>
            <table class="program_table"><tr>
              <td><div class="eventHeader"><h3><big>16</big>&nbsp;&nbsp;Di</h3><p>20:00 Uhr</p></div></td>
              <td><h3 class="eventTitel"><a href="index.php?prod=566">$title</a></h3><p class="teaserText">$teaser</p></td>
              <td><span class="hideOnMobile">$labelCell</span><span class="kein-ticket-link"> </span></td>
            </tr></table>
            """.trimIndent()
        return scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()
    }

    private fun typeOfUnlabelled(
        title: String,
        teaser: String
    ): String? = scrapedRow(title, teaser).eventType

    @Test
    fun `discovers one event per performance, not per production`() {
        // 24 rows across five month headings, resolving to 14 productions.
        events shouldHaveSize 24
        events.map { it.sourceUrl }.distinct() shouldHaveSize 14
    }

    @Test
    fun `maps a fully populated row`() {
        val stokes = event("theater_im_delphi:525/2026-11-14-19:30")
        stokes.title shouldBe "Genevieve Stokes"
        stokes.eventType shouldBe EventType.CONCERT.name
        stokes.eventDate shouldBe LocalDate.of(2026, 11, 14)
        stokes.startTime shouldBe LocalTime.of(19, 30)
        stokes.sourceUrl shouldBe "https://theater-im-delphi.de/programm/?prod=525"
        stokes.imageUrl!! shouldStartWith "https://theater-im-delphi.de/wp-content/uploads/programm/"
        stokes.description!!.isNotBlank() shouldBe true
        stokes.artists.map { it.name } shouldBe listOf("Genevieve Stokes")
    }

    @Test
    fun `takes the month and year from the heading above the table`() {
        // The row states only the day; "August 2026" / "Dezember 2026" supply the rest.
        event("theater_im_delphi:528/2026-08-09-18:00").eventDate shouldBe LocalDate.of(2026, 8, 9)
        event("theater_im_delphi:519/2026-12-10-19:30").eventDate shouldBe LocalDate.of(2026, 12, 10)
        events.none { it.eventDate == UNRESOLVED_EVENT_DATE } shouldBe true
        events.none { it.startTime == null } shouldBe true
    }

    @Test
    fun `keeps a matinee and an evening of the same production apart`() {
        // Four productions play twice in one day; only the clock distinguishes them.
        val twentySeventh = events.filter { it.eventDate == LocalDate.of(2026, 9, 27) }
        twentySeventh shouldHaveSize 2
        twentySeventh.map { it.startTime } shouldBe listOf(LocalTime.of(15, 0), LocalTime.of(20, 0))
        twentySeventh.map { it.sourceId } shouldBe
            listOf("theater_im_delphi:488/2026-09-27-15:00", "theater_im_delphi:488/2026-09-27-20:00")
        events.map { it.sourceId }.toSet() shouldHaveSize 24
    }

    @Test
    fun `types the venue's staging formats`() {
        // The model has no dance or theatre type, so both are staged shows.
        event("theater_im_delphi:488/2026-09-27-15:00").eventType shouldBe EventType.SHOW.name
        event("theater_im_delphi:531/2026-10-13-11:00").eventType shouldBe EventType.SHOW.name
        event("theater_im_delphi:538/2026-09-13-12:00").eventType shouldBe EventType.READING.name
        event("theater_im_delphi:512/2026-10-08-19:00").eventType shouldBe EventType.CONCERT.name
    }

    @Test
    fun `types an unlabelled row as a concert from its teaser`() {
        // Both rows carry an empty label cell; the teasers name "Songwriterinnen" and "Folksongs" (#2899).
        val lisa = event("theater_im_delphi:524/2026-10-09-20:00")
        lisa.eventType shouldBe EventType.CONCERT.name
        lisa.artists.map { it.name } shouldBe listOf("LISA O'NEILL")
        val fussell = event("theater_im_delphi:519/2026-12-10-19:30")
        fussell.eventType shouldBe EventType.CONCERT.name
        fussell.artists.map { it.name } shouldBe listOf("Jake Xerxes Fussell")
        events.none { it.eventType == null } shouldBe true
    }

    @Test
    fun `types an unlabelled wrestling night as a show and bills no act`() {
        // The title has no dash, so no solo act is read off it (#3158).
        val wrestling = credits.single { it.sourceId == "theater_im_delphi:546/2026-10-17-17:00" }
        wrestling.eventType shouldBe EventType.SHOW.name
        wrestling.artists.shouldBeEmpty()
    }

    @Test
    fun `types a comedy tour from its Comedy label and bills no act`() {
        // Event 12701, verbatim. Only a concert bills acts, so the title's `- <tour>` split never runs.
        val tour =
            scrapedRow(
                title = "Jordan Prince - Royal Highness Comedy Tour 2027",
                teaser =
                    "Mit seiner einzigartigen Sicht auf das Leben in Deutschland hat Comedian und Content-Creator Jordan Prince zunächst Online " +
                        "ein großes Publikum begeistert, das schon bald auch zahlreich in seine Live-Auftritte strömte. Im Frühjahr 2027 bringt er seinen " +
                        "bissigen, selbstironischen Humor im Rahmen seiner bisher größten Tour erneut auf die Bühne.",
                label = "Comedy & Kabarett"
            )
        tour.eventType shouldBe EventType.COMEDY.name
        tour.artists.shouldBeEmpty()
    }

    @Test
    fun `types a drag show from its Revue label and bills no act`() {
        // Events 12691 and 12692, verbatim. `by DUCT TAPE` names the producer, not an act.
        val drag =
            scrapedRow(
                title = "A Berlin Drag Queens CHRISTMAS SHOW by DUCT TAPE",
                teaser =
                    "Dieses Weihnachten bringt DUCT TAPE die Berliner Drag-Szene für zwei ganz besondere Abende im Theater im Delphi zusammen.<br />\n" +
                        "Am 4. und 5. Dezember wird Weihnachten gefeiert mit zwei unterschiedlichen gigantischen Berlin Allstars Line-ups. ",
                label = "Revue"
            )
        drag.eventType shouldBe EventType.SHOW.name
        drag.artists.shouldBeEmpty()
    }

    @Test
    fun `types an unlabelled music tour as a concert and bills the act and its guest`() {
        // Event 12700, verbatim.
        val tour =
            scrapedRow(
                title = "Mark Eitzel (+ Our Man In The Field)",
                teaser =
                    "Mark Eitzel (American Music Club) kommt für seine erste Tournee seit zehn Jahren nach Deutschland. Neben älteren Klassikern des " +
                        "American Music Club, die auf einem neuen Box-Set erscheinen werden, wird Mark Songs aus früheren Soloalben und einer neuen Vinyl-EP spielen."
            )
        tour.eventType shouldBe EventType.CONCERT.name
        tour.artists.map { it.name to it.role } shouldBe
            listOf("Mark Eitzel" to ArtistRole.HEADLINER, "Our Man In The Field" to ArtistRole.SUPPORT)
    }

    @Test
    fun `types an unlabelled row as comedy from a word in its title or teaser`() {
        typeOfUnlabelled("Royal Highness Comedy Tour", "Zehn Abende.") shouldBe EventType.COMEDY.name
        typeOfUnlabelled("Royal Highness", "Mit dem Comedian Max Beispiel.") shouldBe EventType.COMEDY.name
    }

    @Test
    fun `keeps a vocal ensemble's name out of the comedy cue`() {
        typeOfUnlabelled("Die Comedian Harmonists", "Ein Abend mit ihren Liedern.") shouldBe EventType.OTHER.name
    }

    @Test
    fun `types an unlabelled drag title as a show, even where the teaser names comedy`() {
        // The production text of events 12691 and 12692 reads "voller Drag, Comedy, Live-Gesang".
        typeOfUnlabelled("Berlin Drag Night", "Ein Spektakel voller Drag und Comedy.") shouldBe EventType.SHOW.name
        typeOfUnlabelled("Dragshow Berlin", "Ein Abend.") shouldBe EventType.SHOW.name
    }

    @Test
    fun `does not read drag out of a band's name`() {
        // Events 12694 and 12695 carry this title.
        typeOfUnlabelled("Imagine Dragons Tribute", "Ein Abend.") shouldBe EventType.OTHER.name
    }

    @Test
    fun `leaves a theatre tour without songs or an album out of the concerts`() {
        typeOfUnlabelled("Der Besuch", "Das Ensemble kommt auf seiner Tournee nach Berlin.") shouldBe EventType.OTHER.name
        typeOfUnlabelled("Neue Songs", "Ein Abend in Berlin.") shouldBe EventType.OTHER.name
    }

    @Test
    fun `types the Varieté and Performance labels as shows`() {
        val luxorium = variete.filter { it.title == "The Luxorium – Grand Varieté" }
        luxorium.map { it.sourceId } shouldBe
            listOf(
                "theater_im_delphi:553/2026-11-27-19:00",
                "theater_im_delphi:553/2026-11-28-18:00",
                "theater_im_delphi:553/2026-11-29-18:00"
            )
        luxorium.map { it.eventType }.distinct() shouldBe listOf(EventType.SHOW.name)
        luxorium.first().genre.shouldBeNull()
        val festival = variete.single { it.sourceId == "theater_im_delphi:551/2026-11-07-20:00" }
        festival.eventType shouldBe EventType.SHOW.name
        festival.artists.shouldBeEmpty()
    }

    @Test
    fun `bills no act on a staged row, whose title names the production`() {
        // `<Name> – <Show>` reads as a performer's bill elsewhere (#315); at a theatre it is a production (#3327).
        val luxorium = variete.filter { it.title == "The Luxorium – Grand Varieté" }
        luxorium shouldHaveSize 3
        luxorium.forEach { it.artists.shouldBeEmpty() }
        val jubilee = variete.single { it.title == "Happy Jubilee – Eine Tanzrevue zum Jubiläum" }
        jubilee.sourceId shouldBe "theater_im_delphi:534/2026-12-13-18:00"
        jubilee.eventType shouldBe EventType.SHOW.name
        // The credit line names the company, not an act (#2899).
        jubilee.artists.shouldBeEmpty()
        val drag = events.filter { it.title == "König Theater – Drag King Theater Show" }
        drag shouldHaveSize 2
        drag.forEach { it.artists.shouldBeEmpty() }
    }

    @Test
    fun `bills a concert's credit line, not its programme title`() {
        // The title names the programme; `p.stabText` names who plays it (#2899).
        event("theater_im_delphi:512/2026-10-08-19:00").artists.map { it.name } shouldBe
            listOf("Ensemble Utak", "Hannah Dienes-Williams", "R. Marino Arcaro")
    }

    @Test
    fun `leaves the composer and the film's director off a credit line`() {
        // The teaser credits "Musik von Johannes Brahms" and "(1922, F.W. Murnau)": authors, not performers.
        val nosferatu = credits.single { it.title == "Nosferatu. Eine Symphonie des Grauens" }
        nosferatu.eventType shouldBe EventType.CONCERT.name
        nosferatu.artists.map { it.name } shouldBe listOf("Golden Star String Quartet", "Katarzyna Marek")
        nosferatu.artists.map { it.role }.distinct() shouldBe listOf(ArtistRole.HEADLINER)
    }

    @Test
    fun `ignores a staged row's credit line, which names the company`() {
        event("theater_im_delphi:531/2026-10-13-11:00").artists.map { it.name } shouldNotContain "FLUIDUM"
    }

    @Test
    fun `stores a genre only where the label names one`() {
        // "Kammermusik" and "Elektronische Musik" are genres; "Tanz" and "Theater" are formats.
        event("theater_im_delphi:512/2026-10-08-19:00").genre shouldBe "Kammermusik"
        event("theater_im_delphi:528/2026-08-09-18:00").genre shouldBe "Elektronische Musik"
        event("theater_im_delphi:488/2026-09-27-15:00").genre.shouldBeNull()
        events.count { it.genre != null } shouldBe 2
    }

    @Test
    fun `recovers prices from the page's leaked record dump`() {
        // The rendered page states no price anywhere; the var_dump comment is the only source.
        val prometheus = event("theater_im_delphi:531/2026-10-13-11:00")
        prometheus.pricePresale shouldBe BigDecimal("5")
        prometheus.priceNote shouldBe "5–18 €"
        // A flat price renders without a range.
        val lisa = event("theater_im_delphi:524/2026-10-09-20:00")
        lisa.pricePresale shouldBe BigDecimal("34.75")
        lisa.priceNote shouldBe "34,75 €"
    }

    @Test
    fun `prefers the production amounts, which carry the cents the per-event copies round away`() {
        // The dump states event 29/32 and production 29.95/32.45 for the same performance.
        val swanLake = event("theater_im_delphi:488/2026-09-27-15:00")
        swanLake.pricePresale shouldBe BigDecimal("29.95")
        swanLake.priceNote shouldBe "29,95–32,45 €"
    }

    @Test
    fun `reads the free-entry flag and stores no price for those`() {
        val free = events.filter { it.free }
        free.map { it.title } shouldBe
            listOf("Delphis Orakel x Makoto Sakamoto", "Tag des offenen Denkmals", "SheLeadsNature: Strukturen ändern, nicht die Frauen!")
        free.forEach {
            it.pricePresale.shouldBeNull()
            it.priceNote.shouldBeNull()
        }
    }

    @Test
    fun `reads each row's own ticket-shop link`() {
        // The venue sells through whichever shop the production uses, per row.
        event("theater_im_delphi:525/2026-11-14-19:30").ticketUrl!! shouldStartWith "https://www.eventim.de/"
        event("theater_im_delphi:531/2026-10-13-11:00").ticketUrl!! shouldStartWith "https://19871.reservix.de/"
        event("theater_im_delphi:519/2026-12-10-19:30").ticketUrl!! shouldStartWith "https://www.tixforgigs.com/"
        // A free date renders a `.kein-ticket-link` placeholder with no href.
        event("theater_im_delphi:528/2026-08-09-18:00").ticketUrl.shouldBeNull()
        events.count { it.ticketUrl == null } shouldBe 2
    }

    @Test
    fun `joins the dump on production and start time, so every paid date gets its price`() {
        events.count { it.pricePresale != null } shouldBe 21
        events.count { it.pricePresale == null } shouldBe 3
    }

    @Test
    fun `survives a page whose leaked dump has been fixed away`() {
        // The leak is an accident; losing it must cost only the prices.
        val html = fixtureText("delphi/delphi-programm.html")
        val withoutDump = html.replace(Regex("""<!--array\(23\).*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        val stripped = scraper.scrape(Jsoup.parse(withoutDump, baseUrl), baseUrl)

        stripped shouldHaveSize 24
        stripped.none { it.pricePresale != null } shouldBe true
        stripped.none { it.free } shouldBe true
        stripped.first().title shouldBe "Delphis Orakel x Makoto Sakamoto"
    }

    @Test
    fun `returns an empty list for a page without a programme`() {
        val document = Jsoup.parse("<html><body><section class='events_section'></section></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}
