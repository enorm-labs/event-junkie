package de.norm.events.scraper.paloma

import de.norm.events.scraper.BERLIN
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

/** Unit tests for [PalomaProgrammPageScraper], against a saved copy of the real programme. */
class PalomaProgrammPageScraperTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), BERLIN)
    private val scraper = PalomaProgrammPageScraper(clock)
    private val baseUrl = "https://www.palomabar.de/programm.php"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/paloma/paloma-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun djs(date: String) = night(date).artists.map { it.name }

    @Test
    fun `scrape reads every night of the programme`() {
        events shouldHaveSize 10
        events.map { it.eventDate.dayOfMonth } shouldContainExactly listOf(16, 17, 18, 22, 23, 24, 25, 29, 30, 31)
        events.map { it.eventDate.year }.distinct() shouldContainExactly listOf(2026)
    }

    @Test
    fun `scrape maps a night with both parties in the title and every DJ`() {
        val event = night("2026-10-16")

        event.title shouldBe "OPERATE / SCION VERSIONS"
        event.eventType shouldBe "PARTY"
        event.startTime shouldBe LocalTime.of(22, 0)
        event.doorsTime.shouldBeNull()
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "paloma:2026-10-16"
        event.artists.map { it.role }.distinct() shouldContainExactly listOf("DJ")
        djs("2026-10-16") shouldContainExactly listOf("TMSN", "BEN MONO", "MATTIK", "CAMPBELL", "DJ PETE", "RENÉ LÖWE")
    }

    @Test
    fun `a night whose first party cell is empty takes its title from the next one`() {
        val event = night("2026-10-18")

        event.title shouldBe "OYSTER HOUR"
        event.startTime shouldBe LocalTime.of(15, 0)
        djs("2026-10-18") shouldContainExactly listOf("KARETE BU", "MAX I.", "ROBIN KOCH", "JAVIER BÄHR", "LEZARDS")
    }

    @Test
    fun `a party named twice in one night is titled once`() {
        night("2026-10-25").title shouldBe "FLEEZE - FLIRT RECORDS & BREEZE"
    }

    @Test
    fun `a DJ without links is still billed`() {
        djs("2026-10-23") shouldContainExactly listOf("CHO ROOM", "SPRITEEYEZ", "ANROLE", "LAVAN", "AQWEA")
    }

    @Test
    fun `the year rolls over for a January night`() {
        val html =
            """
            <div><div><b>FREITAG 01/01 23:00</b></div>
            <div><table><tr><td><b><div>NEW YEAR</div></b></td></tr><tr><td class=kotti_online_td>DJ ONE&nbsp;</td></tr></table></div></div>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        event.eventDate shouldBe LocalDate.of(2027, 1, 1)
        event.title shouldBe "NEW YEAR"
    }

    @Test
    fun `a page without a programme yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Closed</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
