package de.norm.events.scraper.silentgreen

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [SilentGreenMonthPageScraper], parsing saved snapshots of the `/programm`
 * calendar for August and September 2026 and of an empty future month.
 */
class SilentGreenMonthPageScraperTest {
    private val scraper = SilentGreenMonthPageScraper()

    private val augustUrl = "https://www.silent-green.net/programm"
    private val septemberUrl = "https://www.silent-green.net/programm/2026/9"
    private val emptyUrl = "https://www.silent-green.net/programm/2027/1"

    private fun parse(
        fixture: String,
        baseUrl: String
    ): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/silentgreen/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private val august by lazy { parse("silentgreen-month-august.html", augustUrl) }
    private val september by lazy { parse("silentgreen-month-september.html", septemberUrl) }

    @Test
    fun `scrape extracts one event per listed calendar day`() {
        august shouldHaveSize 53
    }

    @Test
    fun `scrape maps every calendar field of a concert row`() {
        val concert = august.first { it.eventDate == LocalDate.of(2026, 8, 2) && it.eventType == EventType.CONCERT.name }

        concert.title shouldBe "HTRK + Loraine James"
        concert.eventDate shouldBe LocalDate.of(2026, 8, 2)
        concert.startTime shouldBe LocalTime.of(19, 45)
        concert.sourceUrl shouldBe "https://www.silent-green.net/programm/detail/htrk"
        concert.sourceId shouldBe "silent_green:2026-08-02-htrk"
        concert.ticketUrl shouldBe "https://ra.co/events/2478204"
        concert.soldOut shouldBe true
        concert.status shouldBe "SCHEDULED"
        concert.promoters shouldContainExactly listOf("Berlin Atonal", "silent green")
        concert.artists.map { it.name } shouldContainExactly listOf("HTRK", "Loraine James")
        concert.artists.map { it.stage }.distinct() shouldContainExactly listOf("Kuppelhalle")
    }

    @Test
    fun `scrape leaves detail-page fields unset - they are merged in by the importer`() {
        val concert = august.first { it.sourceId == "silent_green:2026-08-02-htrk" }

        concert.doorsTime.shouldBeNull()
        concert.description.shouldBeNull()
        concert.imageUrl.shouldBeNull()
    }

    @Test
    fun `scrape stores no prices or genre - the venue publishes neither`() {
        august.all { it.pricePresale == null && it.priceBoxOffice == null && it.priceNote == null } shouldBe true
        august.all { it.genre == null } shouldBe true
    }

    @Test
    fun `scrape lists a multi-day run once per open day, sharing its detail page but not its sourceId`() {
        val exhibition = august.filter { it.sourceUrl.endsWith("/bjoern-melhus-lost-in-finity") }

        exhibition shouldHaveSize 23
        exhibition.map { it.title }.distinct() shouldContainExactly listOf("Bjørn Melhus: LOST IN FINITY")
        exhibition.map { it.sourceId }.distinct() shouldHaveSize exhibition.size
        exhibition.first().sourceId shouldBe "silent_green:2026-08-01-bjoern-melhus-lost-in-finity"
        exhibition.all { it.eventType == EventType.EXHIBITION.name } shouldBe true
    }

    @Test
    fun `scrape takes the year from the month heading and the day and month from the row`() {
        september.map { it.eventDate }.distinct().all { it.year == 2026 && it.monthValue == 9 } shouldBe true
        september.first().eventDate shouldBe LocalDate.of(2026, 9, 2)
    }

    @Test
    fun `scrape drops the calendar's tx_news query parameters from the detail link`() {
        august.all { !it.sourceUrl.contains('?') } shouldBe true
    }

    @Test
    fun `scrape ranks a multi-format category label by strength, not by the order the venue writes it`() {
        // "Panel, Lesung, Festival, Konzert" is the festival it says it is …
        august.first { it.title == "Pop-Kultur Festival 2026" }.eventType shouldBe EventType.FESTIVAL.name
        // … "Konzert, Ausstellung" is the exhibition opening, whose live-set names must not become artists …
        val opening = august.first { it.sourceUrl.endsWith("-feat-pole-jakojako-ruben-nsue-sunroof-nicolas-bougaeiff") }
        opening.eventType shouldBe EventType.EXHIBITION.name
        opening.artists.shouldHaveSize(0)
        // … "Artist-Talk, Filmvorführung" is a screening, and "Filmvorführung, Konzert" a film concert.
        august.first { it.title.endsWith("THE BEGINNING OF THE END") }.eventType shouldBe EventType.SCREENING.name
        september.first { it.title.startsWith("Filmkonzert:") }.eventType shouldBe EventType.SCREENING.name
    }

    @Test
    fun `scrape maps the venue's own spoken-word and conference labels`() {
        september.first { it.title.startsWith("Buchpremiere GAVDOS") }.eventType shouldBe EventType.READING.name
        september.first { it.title.startsWith("Ilona Hartmann") }.eventType shouldBe EventType.READING.name
        september.first { it.title.startsWith("Zia Kongress") }.eventType shouldBe EventType.OTHER.name
        august.first { it.title.contains("DEADLY FRUITS") }.eventType shouldBe EventType.READING.name
    }

    @Test
    fun `scrape files an uncategorised row as OTHER rather than presuming a concert`() {
        val sommerfest = august.first { it.title == "silent green Sommerfest 2026" }

        sommerfest.eventType shouldBe EventType.OTHER.name
        sommerfest.artists.shouldHaveSize(0)
        september.filter { it.title.startsWith("Historische Führungen") }.all { it.eventType == EventType.OTHER.name } shouldBe true
    }

    @Test
    fun `scrape reads the credit line as promoters, splitting only on comma and ampersand`() {
        val event = september.first { it.title == "Curbside Lambsear" }

        // "silent green, Mansions and Millions & Puschen präsentieren" — the label's own "and" is not a separator.
        event.promoters shouldContainExactly listOf("silent green", "Mansions and Millions", "Puschen")
        event.subtitle.shouldBeNull()
    }

    @Test
    fun `scrape keeps a genuine sub-line as the subtitle`() {
        val congress = september.first { it.title.startsWith("ZEIT WISSEN Kongress") }

        congress.subtitle shouldBe "Zukunft. Sicher. Gestalten."
        congress.promoters.shouldHaveSize(0)
    }

    @Test
    fun `scrape strips the host and series that lead a title from the derived artists only`() {
        val hosted = september.first { it.title == "hub pres. Doorman + Franco Franco" }
        hosted.artists.map { it.name } shouldContainExactly listOf("Doorman", "Franco Franco")
        // An act presenting its own project keeps the act; the venue presenting a programme keeps the programme (#1581).
        september.first { it.title == "Burnt Friedman pres. Secret Rhythms" }.artists.map { it.name } shouldContainExactly
            listOf("Burnt Friedman")
        august.first { it.title == "silent green pres. Mutant Radio Sessions" }.artists.map { it.name } shouldContainExactly
            listOf("Mutant Radio Sessions")

        val series = september.first { it.title.startsWith("15 YEARS zweikommasieben") }
        series.artists.map { it.name } shouldContainExactly listOf("Anna Homler", "Steven Warwick", "zweikommasieben DJs")

        // A colon with a single name after it is a title, not a series and its act — so it stays whole.
        val single = august.first { it.title == "The I in the Mirror: Reflection" }
        single.artists.map { it.name } shouldContainExactly listOf("The I in the Mirror: Reflection")
    }

    @Test
    fun `scrape reads a sold-out annotation as the flag and strips it from the title`() {
        val soldOut = september.first { it.eventDate == LocalDate.of(2026, 9, 26) }

        soldOut.title shouldBe "Current 93 – Sonic Morgue"
        soldOut.soldOut shouldBe true
        september.first { it.eventDate == LocalDate.of(2026, 9, 27) }.soldOut shouldBe false
    }

    @Test
    fun `scrape returns nothing for a month with no programme`() {
        parse("silentgreen-month-empty.html", emptyUrl).shouldHaveSize(0)
    }

    @Test
    fun `scrape returns nothing when the month heading cannot be read`() {
        val document = Jsoup.parse("<html><body><div class='eventList-day'><div class='eventList-event'></div></div></body></html>", augustUrl)

        scraper.scrape(document, augustUrl).shouldHaveSize(0)
    }
}
