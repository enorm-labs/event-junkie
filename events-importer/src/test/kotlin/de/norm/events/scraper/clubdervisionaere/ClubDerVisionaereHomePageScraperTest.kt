package de.norm.events.scraper.clubdervisionaere

import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * Unit tests for [ClubDerVisionaereHomePageScraper].
 *
 * The fixture is the homepage saved on the same day as `clubdervisionaere-programm-september.html`,
 * so its post ids are the programme's — that pairing is what the importer test joins on.
 */
class ClubDerVisionaereHomePageScraperTest {
    private val scraper = ClubDerVisionaereHomePageScraper()

    private fun fixture(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/clubdervisionaere/$name")!!
                .bufferedReader()
                .readText(),
            "https://clubdervisionaere.com/"
        )

    @Test
    fun `reads one start time per NEXT block, keyed by post id`() {
        val times = scraper.scrape(fixture("clubdervisionaere-home-september.html"))

        times shouldHaveSize 10
        times["7227"] shouldBe LocalTime.of(23, 0) // So. 13.9.   11:00 p.m. — the boat's Sunday night
        times["41877"] shouldBe LocalTime.of(18, 0) // Mo. 14.9.   06:00 p.m.
        times["41852"] shouldBe LocalTime.of(20, 30) // Mo. 14.9.   08:30 p.m. — Omniversal Earkestra Mondays
        times["41871"] shouldBe LocalTime.of(15, 0) // Fr. 18.9.   03:00 p.m.
        times["7228"] shouldBe LocalTime.of(22, 0) // Sa. 26.9.   10:00 p.m.
    }

    @Test
    fun `the TODAY block is not read because it has no post id`() {
        val times = scraper.scrape(fixture("clubdervisionaere-home-september.html"))

        // Today's night on the fixture is post 41860 on the programme; the homepage names it by title only.
        times.keys.contains("41860") shouldBe false
    }

    @Test
    fun `converts the twelve-hour clock at both ends of the dial`() {
        val html =
            """
            <div id="next">
              <div id="post-1" class="theID"><div id="nextDate">Mo. 1.1.&nbsp;&nbsp;&nbsp;12:30 a.m.</div></div>
              <div id="post-2" class="theID"><div id="nextDate">Mo. 1.1.&nbsp;&nbsp;&nbsp;12:00 p.m.</div></div>
              <div id="post-3" class="theID"><div id="nextDate">Mo. 1.1.&nbsp;&nbsp;&nbsp;9:15 PM</div></div>
            </div>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html)) shouldContainExactly
            mapOf(
                "1" to LocalTime.of(0, 30),
                "2" to LocalTime.of(12, 0),
                "3" to LocalTime.of(21, 15)
            )
    }

    @Test
    fun `skips a block whose date cell carries no time`() {
        val html =
            """
            <div id="next">
              <div id="post-1" class="theID"><div id="nextDate">Mo. 1.1.</div></div>
              <div id="post-2" class="theID"><div id="nextDate">Di. 2.1.&nbsp;&nbsp;&nbsp;11:00 p.m.</div></div>
            </div>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html)) shouldContainExactly mapOf("2" to LocalTime.of(23, 0))
    }

    @Test
    fun `returns an empty map for a page without a NEXT box`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Nothing on tonight</p></body></html>")).shouldBeEmpty()
    }
}
