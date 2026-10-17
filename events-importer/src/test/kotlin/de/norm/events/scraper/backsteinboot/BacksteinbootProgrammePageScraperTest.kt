package de.norm.events.scraper.backsteinboot

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [BacksteinbootProgrammePageScraper], against saved copies of the programme and the 2026 archive. */
class BacksteinbootProgrammePageScraperTest {
    private val scraper = BacksteinbootProgrammePageScraper()
    private val baseUrl = "https://backsteinboot.org/program-(landing-page)-1"

    private fun scrape(fixture: String): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/backsteinboot/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private val programme by lazy { scrape("backsteinboot-programme.html") }
    private val archive by lazy { scrape("backsteinboot-archive-2026.html") }

    private fun snippet(
        dateTime: String,
        format: String,
        heading: String = "October 2026"
    ) = """
        <hr><span class="title">$heading<br></span>
        <column-set><column-unit slot="0">
        <span class="subtitle">Night<br></span><span class="date-and-time">$dateTime</span>
        <span class="text">$format<br>artists: One</span>
        </column-unit></column-set>
        """.trimIndent()

    private fun scrapeSnippet(html: String) = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)

    @Test
    fun `scrape keeps the club night and the exhibition and drops workshops and a closed talk`() {
        programme.map { it.title } shouldContainExactly listOf("Hold Space Core Sample", "Climate of Fear")
    }

    @Test
    fun `scrape maps a club night across two days with its DJs, promoter and ticket link`() {
        val event = programme.single { it.title == "Climate of Fear" }

        event.eventType shouldBe "PARTY"
        event.eventDate shouldBe LocalDate.of(2026, 10, 10)
        event.startTime shouldBe LocalTime.of(23, 59)
        event.endDate shouldBe LocalDate.of(2026, 10, 11)
        event.endTime shouldBe LocalTime.of(23, 59)
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "backsteinboot:2026-10-10-climate-of-fear"
        event.ticketUrl shouldBe "https://ra.co/events/2533830"
        event.promoters shouldContainExactly listOf("Climate of Fear")
        event.artists.map { it.name } shouldContainExactly
            listOf("upsammy", "Lechuga Zafiro", "Gabrielle Kwarteng", "Peach", "DJ Travella", "Kikelomo", "Kontronatura", "livwutang")
        event.artists.map { it.role }.distinct() shouldContainExactly listOf("DJ")
        event.imageUrl shouldBe
            "https://freight.cargo.site/w/1200/i/B3159843020242439749934785003645/271a276823d512fb593a315a31b802b9af97aa42-2.jpg"
    }

    @Test
    fun `an exhibition bills no artists and keeps its donation note`() {
        val event = programme.single { it.title == "Hold Space Core Sample" }

        event.eventType shouldBe "EXHIBITION"
        event.eventDate shouldBe LocalDate.of(2026, 10, 4)
        event.startTime shouldBe LocalTime.of(16, 0)
        event.endDate.shouldBeNull()
        event.endTime.shouldBeNull()
        event.priceNote shouldBe "based on donation"
        event.artists.shouldBeEmpty()
        event.promoters.shouldBeEmpty()
    }

    @Test
    fun `the archive maps every format the venue names`() {
        archive.map { it.eventType }.distinct() shouldContainExactly listOf("READING", "EXHIBITION", "SCREENING", "PARTY", "SHOW", "CONCERT")
        archive shouldHaveSize 23
        archive.map { it.title } shouldNotContain "Experimental Drawing Class"
    }

    @Test
    fun `an exhibition run ends on its last day, without an end time`() {
        val event = archive.single { it.title == "FUGUE" }

        event.eventDate shouldBe LocalDate.of(2026, 2, 9)
        event.endDate shouldBe LocalDate.of(2026, 2, 15)
        event.endTime.shouldBeNull()
    }

    @Test
    fun `a lineup over several lines keeps a bracketed note with its act and bills a live act`() {
        val concert = archive.single { it.title == "Appendix.files (Sunday Sessions)" }
        concert.eventType shouldBe "CONCERT"
        concert.artists.map { it.name } shouldContain "Lint."

        val climate = archive.single { it.title == "Climate x SR" }
        climate.artists.map { it.name } shouldContainExactly
            listOf("Aurora Halal", "Kia", "Azu Tiwaline", "BASHKKA", "D. Tiffany", "DJ Marfox", "Kasper Marott", "PLO Man")
        climate.artists.single { it.name == "D. Tiffany" }.role shouldBe "LIVE"
    }

    @Test
    fun `a workshop that a club night follows is kept`() {
        archive.single { it.title == "HyperrTerra Openair + Indoors" }.eventType shouldBe "PARTY"
    }

    @Test
    fun `an entry without a date is skipped`() {
        archive.map { it.title }.filter { "MAGJK" in it }.shouldBeEmpty()
    }

    @Test
    fun `a date range across a month end starts in the earlier month`() {
        val event = scrapeSnippet(snippet("30.10. - 01.11.2026<br>22:00 - 06:00", "CLUB")).single()

        event.eventDate shouldBe LocalDate.of(2026, 10, 30)
        event.endDate shouldBe LocalDate.of(2026, 11, 1)
        event.endTime shouldBe LocalTime.of(6, 0)
    }

    @Test
    fun `a single night ending after midnight ends the next day`() {
        val event = scrapeSnippet(snippet("24.10.2026<br>22:00 - 05:00", "CONCERT")).single()

        event.endDate shouldBe LocalDate.of(2026, 10, 25)
        event.artists.single().role shouldBe "HEADLINER"
    }

    @Test
    fun `a date outside its month heading is skipped, not corrected`() {
        scrapeSnippet(snippet("15.10.2025", "CLUB")).shouldBeEmpty()
    }

    @Test
    fun `an unreadable date is skipped`() {
        scrapeSnippet(snippet("Every Friday<br>22:00 - 05:00", "CLUB")).shouldBeEmpty()
        scrapeSnippet(snippet("31.11.2026", "CLUB")).shouldBeEmpty()
    }

    @Test
    fun `an entry price becomes the box office price`() {
        val html = snippet("24.10.2026", "SCREENINGS").replace("artists: One", "entry: 5€")

        val event = scrapeSnippet(html).single()

        event.priceBoxOffice?.compareTo(BigDecimal(5)) shouldBe 0
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `the format line decides the scope`() {
        BacksteinbootProgrammePageScraper.isParticipationFormat("WORKSHOP") shouldBe true
        BacksteinbootProgrammePageScraper.isParticipationFormat("WORKSHOP, SONIC NEST & CLUB") shouldBe false
        BacksteinbootProgrammePageScraper.isParticipationFormat(null) shouldBe false
        BacksteinbootProgrammePageScraper.eventTypeOf("TALK & DISCUSSIONS") shouldBe "READING"
        BacksteinbootProgrammePageScraper.eventTypeOf("RESIDENCY").shouldBeNull()
    }

    @Test
    fun `a page without a programme yields nothing`() {
        scrapeSnippet("<html><body><p>Closed</p></body></html>").shouldBeEmpty()
    }
}
