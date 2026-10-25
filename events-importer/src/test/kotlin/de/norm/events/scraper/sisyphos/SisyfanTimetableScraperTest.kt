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
    fun `a concert marker is not part of the name`() {
        val names = weekend.artists.map { it.name }
        names.filter { it.contains("konzert", ignoreCase = true) }.shouldBeEmpty()
        names.contains("Tegel Boys") shouldBe true
        names.contains("The Magic Touch") shouldBe true
    }

    @Test
    fun `either spelling of the concert marker goes, with an asterisk missing or beside a live suffix`() {
        val billed =
            listOf(
                "Matze Sampler *KONZERT*",
                "Tasheeno *Konzert*",
                "Die Ganz Lange Kapelle Vom Kleinen Fluss Am Großen See *konzert",
                "Ohne Anfang Konzert*",
                "An On Bast - Live *KONZERT*",
                "TAMADA *Konzert* (live)",
                "Micha Stahl",
                "Hauskonzert"
            )
        actsOf(billed) shouldContainExactly
            listOf(
                "Matze Sampler",
                "Tasheeno",
                "Die Ganz Lange Kapelle Vom Kleinen Fluss Am Großen See",
                "Ohne Anfang",
                "An On Bast",
                "TAMADA",
                "Micha Stahl",
                "Hauskonzert"
            )
    }

    @Test
    fun `a bare trailing Konzert without an asterisk stays in the name`() {
        actsOf(listOf("Tanz Konzert")) shouldContainExactly listOf("Tanz Konzert")
    }

    @Test
    fun `a hybrid or liveact marker is not part of the name, in any case and with an asterisk missing`() {
        val billed =
            listOf(
                "Christopher Coe *Hybrid",
                "Chow Chow Disco *Liveact",
                "Yetti Meissner *HYBRID*",
                "Jonas Xenon liveact*",
                "Oort *Hybrid* *Konzert*",
                "Hybrid Minds",
                "Liveact Larry"
            )
        actsOf(billed) shouldContainExactly
            listOf("Christopher Coe", "Chow Chow Disco", "Yetti Meissner", "Jonas Xenon", "Oort", "Hybrid Minds", "Liveact Larry")
    }

    @Test
    fun `a slot named only by a marker bills no act`() {
        actsOf(listOf("Madame Charlie", "*Secret", "*SECRET*", "Secret*", "*Konzert*", "*Hybrid", "Oort")) shouldContainExactly
            listOf("Madame Charlie", "Oort")
    }

    @Test
    fun `a name that only contains Secret stays`() {
        actsOf(listOf("Secret Society", "Secret")) shouldContainExactly listOf("Secret Society", "Secret")
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

    /** The act names of a one-floor weekend that bills each of [names] in a slot of its own. */
    private fun actsOf(names: List<String>): List<String> {
        val rows =
            names.joinToString("") { name ->
                "<tr x-data=\"{ currentStart: '2026-10-23T22:00:00+02:00', currentEnd: '2026-10-24T01:00:00+02:00' }\">" +
                    "<td><a href=\"https://sisy.fan/artists/x\">$name</a></td></tr>"
            }
        val html =
            """
            <div class="mb-8"><div class="sticky-header"><h2>RAVE.IOLI (23.10.2026 - 26.10.2026)</h2>
            <button @click="setActiveTab(1); ${'$'}dispatch('change-tab', 1)">Hammahalle</button></div>
            <div id="dancefloor-1"><table><tbody>$rows</tbody></table></div></div>
            """.trimIndent()
        return scraper
            .scrape(Jsoup.parse(html))
            .single()
            .artists
            .map { it.name }
    }
}
