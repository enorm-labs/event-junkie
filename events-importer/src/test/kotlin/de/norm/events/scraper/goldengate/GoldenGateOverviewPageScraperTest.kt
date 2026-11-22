package de.norm.events.scraper.goldengate

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.event.ArtistRole
import de.norm.events.scraper.AcceptedLimitations
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.splitBackToBack
import de.norm.events.scraper.withWeekdayWarnings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [GoldenGateOverviewPageScraper].
 *
 * Uses a real homepage snapshot of the announced Thursday–Saturday block, plus hand-built documents
 * for the shapes the live page does not currently show (a night with no lineup, a date line without
 * a time). The fixture is what proves the parser keys off heading *content* rather than Elementor's
 * per-element hashes: the page's trailing non-event headings ("Tickets only available at the
 * door.", "SHOPPING") sit in the same heading stream and must not be read as a night.
 */
class GoldenGateOverviewPageScraperTest {
    private val scraper = GoldenGateOverviewPageScraper()
    private val baseUrl = "https://goldengate-berlin.de/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        events = scraper.scrape(fixtureDocument("goldengate/goldengate-overview.html", baseUrl), baseUrl)
    }

    private fun headings(vararg texts: String) =
        Jsoup.parse(
            texts.joinToString("") { """<h2 class="elementor-heading-title">$it</h2>""" },
            baseUrl
        )

    private fun <T> withScraperWarnings(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(GoldenGateOverviewPageScraper::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block() to appender.list.filter { it.level == Level.WARN }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `stores every night Techno, House, the club's sound, since the venue names no style`() {
        AcceptedLimitations.withHouseGenre(EventSource.GOLDEN_GATE, events).map { it.genre }.distinct() shouldBe listOf("Techno, House")
    }

    @Test
    fun `extracts the announced Thursday to Saturday block`() {
        events shouldHaveSize 3
        events.map { it.eventDate } shouldContainExactly
            listOf(
                LocalDate.of(2026, 7, 30),
                LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 8, 1)
            )
    }

    @Test
    fun `parses every field of a night`() {
        val donnerdogge = events.first()
        donnerdogge.title shouldBe "Donnerdogge"
        donnerdogge.eventType shouldBe "PARTY"
        donnerdogge.eventDate shouldBe LocalDate.of(2026, 7, 30)
        donnerdogge.startTime shouldBe LocalTime.of(23, 59)
        donnerdogge.sourceUrl shouldBe baseUrl
        donnerdogge.sourceId shouldBe "golden_gate:2026-07-30-donnerdogge"
        donnerdogge.status shouldBe "SCHEDULED"
        // The venue publishes none of these anywhere on the page.
        donnerdogge.description.shouldBeNull()
        donnerdogge.imageUrl.shouldBeNull()
        donnerdogge.ticketUrl.shouldBeNull()
        donnerdogge.pricePresale.shouldBeNull()
        donnerdogge.artists shouldContainExactly
            listOf(
                ScrapedArtist("Neco", ArtistRole.DJ),
                ScrapedArtist("Jeremy Reinhard", ArtistRole.DJ),
                ScrapedArtist("Yannick Robyns", ArtistRole.DJ)
            )
    }

    @Test
    fun `splits a back-to-back billing into two DJs`() {
        events[1].artists shouldContainExactly
            listOf(
                ScrapedArtist("Sean Dixon", ArtistRole.DJ),
                ScrapedArtist("Koljah", ArtistRole.DJ),
                ScrapedArtist("Nyna Curtis", ArtistRole.DJ),
                ScrapedArtist("Kisling", ArtistRole.DJ),
                ScrapedArtist("Berunth", ArtistRole.DJ)
            )
    }

    @Test
    fun `splits two DJs a spaced slash joins`() {
        val parsed = scraper.scrape(headings("Do. 29. Oktober 2026 - 23:59", "Anxiety Night", "Lolu Menayed<br>Gingerblack / Sharhok Dini"), baseUrl)
        parsed.single().artists.map { it.name } shouldContainExactly listOf("Lolu Menayed", "Gingerblack", "Sharhok Dini")
    }

    @Test
    fun `a b2b slot reaches the import boundary whole, which splits it into two DJs`() {
        val parsed = scraper.scrape(headings("Do. 01. Oktober 2026 - 23:59", "Cura", "Thor Rixon<br>Anaté b2b Shira Kela"), baseUrl)
        parsed.single().artists.map { it.name } shouldContainExactly listOf("Thor Rixon", "Anaté b2b Shira Kela")
        parsed
            .single()
            .artists
            .flatMap { splitBackToBack(it) }
            .map { it.name } shouldContainExactly listOf("Thor Rixon", "Anaté", "Shira Kela")
    }

    @Test
    fun `distinguishes two nights that share a title by their date`() {
        val klubnaechte = events.filter { it.title == "Klubnacht" }
        klubnaechte shouldHaveSize 2
        klubnaechte.map { it.sourceId } shouldContainExactly
            listOf("golden_gate:2026-07-31-klubnacht", "golden_gate:2026-08-01-klubnacht")
    }

    @Test
    fun `ignores the page's trailing non-event headings`() {
        events.map { it.title } shouldContainExactly listOf("Donnerdogge", "Klubnacht", "Klubnacht")
    }

    @Test
    fun `does not read the next night's date line as a lineup`() {
        val parsed = scraper.scrape(headings("Do. 30. Juli 2026 - 23:59", "Donnerdogge", "Fr. 31. Juli 2026 - 23:59", "Klubnacht"), baseUrl)
        parsed shouldHaveSize 2
        parsed[0].title shouldBe "Donnerdogge"
        parsed[0].artists.shouldBeEmpty()
        parsed[1].title shouldBe "Klubnacht"
    }

    @Test
    fun `accepts a date line without a door time`() {
        val parsed = scraper.scrape(headings("Sa. 01. August 2026", "Klubnacht", "SaPu"), baseUrl)
        parsed shouldHaveSize 1
        parsed[0].eventDate shouldBe LocalDate.of(2026, 8, 1)
        parsed[0].startTime.shouldBeNull()
    }

    @Test
    fun `skips a date line with no title heading after it`() {
        scraper.scrape(headings("Sa. 01. August 2026 - 23:59"), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `returns no events for a page without headings`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `moves a night whose weekday names the neighbouring month, as on the live page of 2 October 2026`() {
        // The page printed "Sa. 03. September 2026": 3 September is a Thursday, 3 October the Saturday of the block.
        val (parsed, warnings) = withWeekdayWarnings { scraper.scrape(fixtureDocument("goldengate/goldengate-overview-month-typo.html", baseUrl), baseUrl) }
        parsed.map { it.sourceId } shouldContainExactly
            listOf(
                "golden_gate:2026-10-01-cura",
                "golden_gate:2026-10-02-wohnzimmer030-afterhour",
                "golden_gate:2026-10-03-nightshade-family"
            )
        parsed.last().eventDate shouldBe LocalDate.of(2026, 10, 3)
        val warning = warnings.single().formattedMessage
        warning shouldContain "Sa. 03. September 2026 - 23:59"
        warning shouldContain "2026-09-03"
        warning shouldContain "2026-10-03"
    }

    @Test
    fun `logs nothing for headings whose weekday agrees with the date`() {
        withWeekdayWarnings { scraper.scrape(fixtureDocument("goldengate/goldengate-overview.html", baseUrl), baseUrl) }.second.shouldBeEmpty()
    }

    @Test
    fun `keeps the printed date of a night whose weekday fits no neighbouring month`() {
        // 3 October 2026 is a Saturday; neither 3 September (Thursday) nor 3 November (Tuesday) is a Friday.
        val (parsed, warnings) =
            withWeekdayWarnings {
                scraper.scrape(headings("Do. 01. Oktober 2026 - 23:59", "Cura", "Fr. 03. Oktober 2026 - 23:59", "Klubnacht"), baseUrl)
            }
        parsed.map { it.title to it.eventDate } shouldContainExactly
            listOf("Cura" to LocalDate.of(2026, 10, 1), "Klubnacht" to LocalDate.of(2026, 10, 3))
        warnings.single().formattedMessage shouldContain "Fr. 03. Oktober 2026 - 23:59"
    }

    @Test
    fun `keeps the printed date when the corrected date sits outside the block`() {
        // 3 October fits the weekday, but the block's confirmed night is in late July.
        val parsed = scraper.scrape(headings("Do. 30. Juli 2026 - 23:59", "Donnerdogge", "Sa. 03. September 2026 - 23:59", "Klubnacht"), baseUrl)
        parsed.map { it.eventDate } shouldContainExactly listOf(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 9, 3))
    }

    @Test
    fun `keeps the printed date of a lone night with a mismatched weekday, since nothing confirms the correction`() {
        scraper.scrape(headings("Sa. 03. September 2026 - 23:59", "Klubnacht"), baseUrl).single().eventDate shouldBe LocalDate.of(2026, 9, 3)
    }

    @Test
    fun `stores a block under one range heading as one row from its first day to its last, as on the live page of 22 November 2026`() {
        val (parsed, warnings) =
            withWeekdayWarnings { scraper.scrape(fixtureDocument("goldengate/goldengate-overview-date-range.html", baseUrl), baseUrl) }
        val anniversary = parsed.single()
        anniversary.title shouldBe "24th GG Anniversary"
        anniversary.eventDate shouldBe LocalDate.of(2026, 11, 19)
        anniversary.endDate shouldBe LocalDate.of(2026, 11, 22)
        anniversary.startTime.shouldBeNull()
        anniversary.sourceId shouldBe "golden_gate:2026-11-19-24th-gg-anniversary"
        // 23 roster lines; two back-to-back billings each split in two.
        anniversary.artists shouldHaveSize 25
        anniversary.artists.map { it.role }.distinct() shouldContainExactly listOf(ArtistRole.DJ)
        anniversary.artists.map { it.name }.takeLast(6) shouldContainExactly
            listOf("Najeh", "Click Click", "Nils Ohrmann", "Serene", "Philosofia", "Slurm McKenzie")
        warnings.shouldBeEmpty()
    }

    @Test
    fun `reads the first day of a range in the month before when the range crosses a month`() {
        val parsed = scraper.scrape(headings("So.29 - Di. 1. Dezember 2026", "Klubnacht", "SaPu"), baseUrl).single()
        parsed.eventDate shouldBe LocalDate.of(2026, 11, 29)
        parsed.endDate shouldBe LocalDate.of(2026, 12, 1)
    }

    @Test
    fun `reads the first day of a range in the year before when the range crosses New Year`() {
        val parsed = scraper.scrape(headings("Do.31 - Sa. 2. Januar 2027", "Silvester", "SaPu"), baseUrl).single()
        parsed.eventDate shouldBe LocalDate.of(2026, 12, 31)
        parsed.endDate shouldBe LocalDate.of(2027, 1, 2)
        parsed.sourceId shouldBe "golden_gate:2026-12-31-silvester"
    }

    @Test
    fun `keeps the printed first day of a range whose weekday does not match, and says so`() {
        // 19 November 2026 is a Thursday; neither 19 October (Monday) nor 19 December (Saturday) is a Friday.
        val (parsed, warnings) = withWeekdayWarnings { scraper.scrape(headings("Fr.19 - So. 22. November 2026", "24th GG Anniversary", "Coco"), baseUrl) }
        parsed.single().eventDate shouldBe LocalDate.of(2026, 11, 19)
        parsed.single().endDate shouldBe LocalDate.of(2026, 11, 22)
        warnings.single().formattedMessage shouldContain "Fr.19 - So. 22. November 2026"
    }

    @Test
    fun `reads a single night beside a range heading`() {
        val parsed =
            scraper.scrape(
                headings(
                    "Do.19 - So. 22. November 2026",
                    "24th GG Anniversary",
                    "Anderson<br>Coco",
                    "Do. 26. November 2026 - 23:59",
                    "Donnerdogge",
                    "Neco"
                ),
                baseUrl
            )
        parsed.map { Triple(it.title, it.eventDate, it.endDate) } shouldContainExactly
            listOf(
                Triple("24th GG Anniversary", LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 22)),
                Triple("Donnerdogge", LocalDate.of(2026, 11, 26), null)
            )
        parsed.first().artists.map { it.name } shouldContainExactly listOf("Anderson", "Coco")
    }

    @Test
    fun `warns when a roster stands under a heading no pattern reads as a date`() {
        val (parsed, warnings) =
            withScraperWarnings {
                scraper.scrape(
                    headings("Do 19.–22.11.2026", "24th GG Anniversary", "Anderson<br>Coco<br>Hagel"),
                    baseUrl
                )
            }
        parsed.shouldBeEmpty()
        val warning = warnings.single()
        warning.formattedMessage shouldContain "no date line opens a night"
        warning.keyValuePairs.single { it.key == LogFields.DATE_HEADING }.value shouldBe "Do 19.–22.11.2026"
    }

    @Test
    fun `does not warn about a page between two blocks, whose headings hold no roster`() {
        withScraperWarnings { scraper.scrape(headings("Tickets an der Tür.<br>Tickets available at the door.", "enter", "SHOPPING"), baseUrl) }
            .second
            .shouldBeEmpty()
    }
}
