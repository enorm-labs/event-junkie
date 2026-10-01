package de.norm.events.scraper.sisyphos

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [SisyfanTimetableScraper], against a snapshot of the sisy.fan home page that shows
 * the weekend of 25–28 September 2026 on five floors.
 */
class SisyfanTimetableScraperTest {
    private val scraper = SisyfanTimetableScraper()

    private val weekends: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/sisyphos/sisyfan-home.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, "https://sisy.fan/"))
    }

    private val weekend: ScrapedEvent by lazy { weekends.single() }

    @Test
    fun `the weekend is one party keyed on its Friday`() {
        weekend.title shouldBe "HAPPY RAVE HAPPY LIFE"
        weekend.eventType shouldBe EventType.PARTY.name
        weekend.sourceId shouldBe "sisyphos:2026-09-25"
    }

    @Test
    fun `the event runs from the first set to the last`() {
        weekend.eventDate shouldBe LocalDate.of(2026, 9, 25)
        weekend.startTime shouldBe LocalTime.of(22, 0)
        weekend.endDate shouldBe LocalDate.of(2026, 9, 28)
        weekend.endTime shouldBe LocalTime.of(10, 0)
    }

    @Test
    fun `the weekend page is both the source and the credit`() {
        weekend.sourceUrl shouldBe "https://sisy.fan/events/from/25.09.2026/to/28.09.2026"
        weekend.lineupSourceUrl shouldBe weekend.sourceUrl
    }

    @Test
    fun `every set on every floor is an act, and break rows are none`() {
        weekend.artists shouldHaveSize 54
        weekend.artists.groupingBy { it.stage }.eachCount() shouldBe
            mapOf("Hammahalle" to 10, "Wintergarten" to 20, "Strand" to 10, "Dampfer" to 9, "Tunnel" to 5)
        weekend.artists.map { it.name } shouldNotContain "Break (16h)"
    }

    @Test
    fun `a set carries its start and end`() {
        val set = weekend.artists.first { it.name == "Micha Stahl" }
        set.stage shouldBe "Hammahalle"
        set.setStart shouldBe Instant.parse("2026-09-25T22:00:00Z")
        set.setEnd shouldBe Instant.parse("2026-09-26T01:00:00Z")
    }

    @Test
    fun `a b2b slot gives both acts the slot`() {
        val slot = weekend.artists.filter { it.name == "Freudenthal" || it.name == "Saeuer" }
        slot.map { it.name } shouldContainExactly listOf("Freudenthal", "Saeuer")
        slot.map { it.setStart }.distinct() shouldHaveSize 1
    }

    @Test
    fun `a live suffix is not part of the name`() {
        val names = weekend.artists.map { it.name }
        names.filter { Regex("""\blive\)?$""", RegexOption.IGNORE_CASE).containsMatchIn(it) }.shouldBeEmpty()
        names.contains("An On Bast") shouldBe true
        names.contains("TAMADA") shouldBe true
    }

    @Test
    fun `a weekend with no sets yet is no event`() {
        val html =
            """
            <div class="mb-8"><div class="sticky-header"><h2>NEXT (02.10.2026 - 05.10.2026)</h2>
            <button @click="setActiveTab(1); ${'$'}dispatch('change-tab', 1)">Hammahalle</button></div>
            <div id="dancefloor-1"><table><tbody></tbody></table></div></div>
            """.trimIndent()
        scraper.scrape(Jsoup.parse(html)).shouldBeEmpty()
    }

    @Test
    fun `a page without a weekend heading is no event`() {
        scraper.scrape(Jsoup.parse("<h2>Events</h2>")).shouldBeEmpty()
    }
}
