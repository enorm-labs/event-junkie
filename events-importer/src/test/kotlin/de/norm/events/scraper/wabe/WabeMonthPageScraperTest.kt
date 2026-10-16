package de.norm.events.scraper.wabe

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class WabeMonthPageScraperTest {
    private val scraper = WabeMonthPageScraper()

    private fun monthUrl(month: String) = "https://www.wabe-berlin.info/$month-2026/"

    private fun scrape(month: String): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/wabe/wabe-$month-2026.html")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, monthUrl(month)), monthUrl(month))
    }

    private fun List<ScrapedEvent>.on(date: String) = single { it.eventDate == LocalDate.parse(date) }

    @Test
    fun `reads one event per printed date from every programme row`() {
        // 14 rows on the October page; two print two and three dates.
        scrape("okt") shouldHaveSize 17
        scrape("nov") shouldHaveSize 3
        scrape("dez") shouldHaveSize 3
    }

    @Test
    fun `reads a concert with its own page fully`() {
        val event = scrape("okt").on("2026-10-03")

        event.title shouldBe "All These Feelings"
        event.subtitle shouldBe "Laura Grandy & Band"
        event.eventType shouldBe EventType.CONCERT.name
        event.genre shouldBe "Jazz, Pop"
        event.startTime shouldBe LocalTime.of(20, 0)
        event.priceBoxOffice shouldBe BigDecimal("10")
        event.pricePresale shouldBe BigDecimal("12")
        event.sourceUrl shouldBe "https://www.wabe-berlin.info/okt-2026/3/"
        event.sourceId shouldBe "wabe:2026-10-03-all-these-feelings"
        event.imageUrl shouldBe
            "https://image.jimcdn.com/app/cms/image/transf/dimension=450x10000:format=jpg/path/sf912d3cc5e447890/image/ib2e9b67e15546748/version/1790327837/image.jpg"
        event.artists shouldContainExactly listOf(ScrapedArtist(name = "Laura Grandy"))
    }

    @Test
    fun `reads the right month when the printed one contradicts the weekday`() {
        // The Siegfried row prints "Freitag 2 November" on the October page: Friday is 2 October.
        val siegfried = scrape("okt").filter { it.title == "Siegfried" }

        siegfried.map { it.eventDate } shouldContainExactly listOf(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"))
    }

    @Test
    fun `types a guest theatre's night as a show and credits the theatre`() {
        val iran = scrape("okt").filter { it.title == "I:RAN" }

        iran.map { it.eventDate.dayOfMonth } shouldContainExactly listOf(24, 25, 26)
        iran.first().eventType shouldBe EventType.SHOW.name
        iran.first().promoters shouldContainExactly listOf("Theater unterm Dach")
        iran.first().artists.shouldBeEmpty()
        iran.first().sourceUrl shouldBe monthUrl("okt")
    }

    @Test
    fun `bills the performers when the first line names no show`() {
        val event = scrape("okt").on("2026-10-16")

        event.title shouldBe "Shannon Callahan & Band"
        event.subtitle shouldBe "In Concert"
        event.sourceId shouldBe "wabe:2026-10-16-in-concert"
        event.artists shouldContainExactly listOf(ScrapedArtist(name = "Shannon Callahan"))
    }

    @Test
    fun `splits a pair of full names but keeps a duo billed by first names whole`() {
        scrape("okt").on("2026-10-17").artists.map { it.name } shouldContainExactly listOf("Frank Brunet", "Andreas Peschel")

        val release = scrape("nov").on("2026-11-08")
        release.title shouldBe "Kaey & José"
        release.artists shouldContainExactly
            listOf(ScrapedArtist(name = "Kaey & José"), ScrapedArtist(name = "Erik Leuthäuser", role = "SUPPORT"))
    }

    @Test
    fun `keeps a director's credit out of the artists`() {
        val event = scrape("okt").on("2026-10-10")

        event.subtitle shouldBe "Ein feministisches Monodrama für Männer · Text und Regie: Tjana Thiessenhusen · Valentin Kleinschmidt"
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `reads a musical reading and a music show`() {
        scrape("okt").on("2026-10-08").eventType shouldBe EventType.READING.name

        val openMic = scrape("okt").on("2026-10-07")
        openMic.eventType shouldBe EventType.CONCERT.name
        openMic.genre.shouldBeNull()
        openMic.sourceUrl shouldBe "https://www.wabe-berlin.info/open-mic/"
        openMic.artists shouldContainExactly listOf(ScrapedArtist(name = "Arno Zillmer"))
    }

    @Test
    fun `rolls a January night on the December page into the next year`() {
        val html =
            """
            <div id="content_area"><div class="j-module n j-hgrid">
              <div class="cc-m-hgrid-column"><div class="j-module n j-text"><p>Freitag</p><p><strong>1</strong></p><p>Januar</p><p>19:00</p><p>Jazz</p></div></div>
              <div class="cc-m-hgrid-column"><div class="j-module n j-text"><p><strong>Neujahrskonzert</strong></p></div></div>
              <div class="cc-m-hgrid-column last"><div class="j-module n j-text"><p>Saal</p></div></div>
            </div></div>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, monthUrl("dez")), monthUrl("dez")).single()

        event.eventDate shouldBe LocalDate.parse("2027-01-01")
        event.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `reads nothing from a page without programme rows`() {
        val url = monthUrl("dez")
        scraper.scrape(Jsoup.parse("<div id='content_area'><p>Programm folgt</p></div>", url), url).shouldBeEmpty()
    }
}
