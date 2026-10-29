package de.norm.events.scraper.studioiiii

import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [StudioIiiiOverviewPageScraper], against a saved copy of the real homepage. */
class StudioIiiiOverviewPageScraperTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), BERLIN)
    private val scraper = StudioIiiiOverviewPageScraper(clock)
    private val baseUrl = "https://studioiiii.de/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/studioiiii/studioiiii-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(title: String) = events.single { it.title == title }

    private fun djs(title: String) = night(title).artists.map { it.name }

    private fun cover(
        heading: String,
        title: String = "NIGHT",
        descr: String = "DJ ONE / DJ TWO"
    ): String =
        """
        <div class="t-cover"><div class="t-cover__carrier" data-content-cover-bg="https://static.tildacdn.net/x/flyer.png"></div>
        <div class="t001__wrapper"><div class="t001__uptitle">$heading</div>
        <div class="t001__title"><div>$title</div></div><div class="t001__descr"><div>$descr</div></div></div></div>
        """.trimIndent()

    private fun scrapeOne(html: String) = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

    @Test
    fun `scrape reads every cover block of the programme`() {
        events shouldHaveSize 6
        events.map { it.eventDate } shouldContainExactly
            listOf("2026-10-16", "2026-10-17", "2026-10-17", "2026-10-23", "2026-10-24", "2026-10-31").map(LocalDate::parse)
    }

    @Test
    fun `scrape maps a club night with its two title lines, image and every DJ`() {
        val event = night("EROTIC DISCOUNT CLUB – PORSCHELANE")

        event.eventType shouldBe "PARTY"
        event.typeIsFallback shouldBe false
        event.startTime shouldBe LocalTime.MIDNIGHT
        event.imageUrl shouldBe "https://static.tildacdn.net/tild3637-3866-4634-b835-323461386362/noroot.png"
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "studio_iiii:2026-10-16-erotic-discount-club"
        event.description.shouldBeNull()
        event.artists.map { it.role }.distinct() shouldContainExactly listOf(ArtistRole.DJ)
        djs("EROTIC DISCOUNT CLUB – PORSCHELANE") shouldContainExactly
            listOf("MISS BASHFUL", "DBBD", "PLAYBEYBUNNY", "NYENNEA", "PORSCHELANE", "BABYNYMPH", "ARSEN")
    }

    @Test
    fun `a night of installation credits only is an exhibition with an end time`() {
        val event = night("HIDDEN PROCESSES")

        event.eventType shouldBe "EXHIBITION"
        event.startTime shouldBe LocalTime.of(20, 0)
        event.endDate shouldBe LocalDate.of(2026, 10, 17)
        event.endTime shouldBe LocalTime.of(22, 0)
        event.artists.shouldBeEmpty()
        event.description shouldBe
            "MEDIA ART INSTALLATIONS BY ORNELLA FIERES, KENNEDY + SWAN, ROBERT SEIDEL\nCURATED BY MARCUS BOXLER & PEGGY SCHOENEGGE WITH MKV."
    }

    @Test
    fun `two nights on one date keep separate identities`() {
        night("BOYS NOIZE").sourceId shouldBe "studio_iiii:2026-10-17-boys-noize"
        night("HIDDEN PROCESSES").sourceId shouldBe "studio_iiii:2026-10-17-hidden-processes"
    }

    @Test
    fun `a ticket notice is the description and a more-tba tail bills nobody`() {
        night("BOYS NOIZE").description shouldBe "TICKETS AT THE DOOR."
        djs("BOYS NOIZE") shouldContainExactly listOf("BOYS NOIZE", "ADAME DJ", "MEG10", "TINKERHELL", "TAUBE")
    }

    @Test
    fun `a comma list broken across lines is one line-up beside its installation credit`() {
        night("EXZESS").description shouldBe "VISUAL INSTALLATION BY JOS TAPPE"
        djs("EXZESS") shouldContainExactly listOf("RAPHA GARCES", "C3MSTA", "KEANU", "ROBIN CARBO", "LEWIS")
    }

    @Test
    fun `a title line ending in a dash joins the next and a room tail is the act's stage`() {
        val event = night("STUDIO NIGHT – CARL BERGÉ INVITES")

        event.sourceId shouldBe "studio_iiii:2026-10-24-studio-night"
        event.artists.single { it.name == "YOUNG LYCHEE" }.stage shouldBe "Clubroom"
        djs("STUDIO NIGHT – CARL BERGÉ INVITES") shouldContainExactly listOf("TAI LOKUN", "CHIOMA", "CARL BERGÉ", "FBI", "YOUNG LYCHEE")
    }

    @Test
    fun `a save-the-date without weekday or line-up is a fallback party`() {
        val event = night("STUDIO HALLOWEEN")

        event.eventDate shouldBe LocalDate.of(2026, 10, 31)
        event.startTime shouldBe LocalTime.of(22, 0)
        event.eventType shouldBe "PARTY"
        event.typeIsFallback shouldBe true
        event.description.shouldBeNull()
        event.artists.shouldBeEmpty()
    }

    private fun lineup(vararg lines: String) = scrapeOne(cover("FRI 23 OCTOBER 9PM", descr = lines.joinToString("<br>"))).artists.map { it.name }

    @Test
    fun `an editor note left in the line-up bills nobody`() {
        lineup("DJ ONE", "Clarify the DJ set lineupUse consistent DJ capitalization") shouldContainExactly listOf("DJ ONE")
    }

    @Test
    fun `a bracketed line with an end stop bills the acts inside it`() {
        lineup("(Anna Mccarthy FEAT. Philip Errico & Michael TAPPER).") shouldContainExactly
            listOf("Anna Mccarthy", "Philip Errico", "Michael TAPPER")
    }

    @Test
    fun `an act keeps its own bracket, an abbreviation stop and a joined name`() {
        lineup("SOLO", "SICKBOYRARI (BLACK KRAY)", "Harry Connick Jr.", "DJ SoundCloud b2b McCarthy", "BabyNymph.") shouldContainExactly
            listOf("SOLO", "SICKBOYRARI (BLACK KRAY)", "Harry Connick Jr.", "DJ SoundCloud b2b McCarthy", "BabyNymph")
    }

    @Test
    fun `the year comes from the weekday across new year`() {
        scrapeOne(cover("FRI 1 JANUARY 11PM")).eventDate shouldBe LocalDate.of(2027, 1, 1)
    }

    @Test
    fun `a range that crosses midnight ends the next day`() {
        val event = scrapeOne(cover("SAT 24 OCT 11-6AM"))

        event.startTime shouldBe LocalTime.of(23, 0)
        event.endDate shouldBe LocalDate.of(2026, 10, 25)
        event.endTime shouldBe LocalTime.of(6, 0)
    }

    @Test
    fun `a cover without a readable date or title is skipped`() {
        scraper.scrape(Jsoup.parse(cover("COMING SOON") + cover("SAT 24 OKTOBER 9PM") + cover("SAT 24 OCT", title = ""), baseUrl), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `a page without cover blocks yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Closed</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
