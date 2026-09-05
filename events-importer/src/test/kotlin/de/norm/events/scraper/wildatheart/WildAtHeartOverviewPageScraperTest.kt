package de.norm.events.scraper.wildatheart

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [WildAtHeartOverviewPageScraper].
 *
 * Uses a fixed clock (2026-07-10, just before the fixture's first event) so weekday-based
 * year inference is deterministic (15 July 2026 = Wednesday) and every event counts as
 * upcoming. Parses the real `concerts.php` snapshot plus synthetic fragments for the
 * bandless and empty-page edge cases.
 */
class WildAtHeartOverviewPageScraperTest {
    private val baseUrl = "https://www.wildatheartberlin.de/concerts.php"
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC)
    private val scraper = WildAtHeartOverviewPageScraper(clock)

    private fun programme() =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/wildatheart/wildatheart-concerts.html")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    @Test
    fun `parses every dated event row from the programme page`() {
        // 77 `<tr>` rows carry a `.datum` cell in the fixture.
        scraper.scrape(programme(), baseUrl) shouldHaveSize 77
    }

    @Test
    fun `fully populates a representative concert with headliner and support`() {
        val event = scraper.scrape(programme(), baseUrl).first { it.title == "Foxy" }

        // "Mi 15.07." with no year → 2026 (the year 15 July lands on a Wednesday nearest today).
        event.eventDate shouldBe LocalDate.of(2026, 7, 15)
        event.eventType shouldBe "CONCERT"
        event.genre shouldBe "Punk"
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "wild_at_heart:2026-07-15-foxy"
        event.imageUrl shouldBe "https://www.wildatheartberlin.de/uploads/img/R1771964478Foxy.jpg"
        // No banner on this row → no subtitle, no ticket link, not free.
        event.subtitle.shouldBeNull()
        event.ticketUrl.shouldBeNull()
        event.startTime.shouldBeNull()
        event.free shouldBe false

        event.artists shouldHaveSize 2
        event.artists[0].name shouldBe "Foxy"
        event.artists[0].role shouldBe "HEADLINER"
        event.artists[1].name shouldBe "Flatfoot 56"
        event.artists[1].role shouldBe "SUPPORT"
    }

    @Test
    fun `extracts ticket link and subtitle from a headline banner and a DJ from the lineup`() {
        val event = scraper.scrape(programme(), baseUrl).first { it.title == "Zeit Driver" }

        event.eventDate shouldBe LocalDate.of(2026, 7, 17)
        event.genre shouldBe "Heavy Rock"
        // The banner supplies the subtitle; its embedded `Tickets:<url>` link is lifted out and stripped.
        event.subtitle shouldBe "Single Release Party"
        event.ticketUrl shouldBe "https://www.eventim-light.com/de/a/6a0205748da5f9f70d30f7e0"

        // Lineup: headliner, support act, then the aftershow DJ.
        event.artists.map { it.name to it.role } shouldBe
            listOf(
                "Zeit Driver" to "HEADLINER",
                "The Birch" to "SUPPORT",
                "DJ Lobotomy" to "DJ"
            )
    }

    @Test
    fun `flags a Wild Wednesday free-entry night and starts it from the banner time`() {
        val event = scraper.scrape(programme(), baseUrl).first { it.title == "La Retraite de la Peur" }

        event.eventDate shouldBe LocalDate.of(2026, 9, 9)
        event.free shouldBe true
        event.startTime shouldBe LocalTime.of(21, 0)
        event.subtitle.shouldNotBeNull() shouldContain "WILD WEDNESDAY"
    }

    @Test
    fun `drops a plus-Guest support slot, keeping only the headliner`() {
        // "Bash Tru" (Fr 07.08.) lists an unannounced support as `+Guest` — a billing
        // placeholder, not a performer — so it is dropped; the headliner and aftershow DJ remain.
        val event = scraper.scrape(programme(), baseUrl).first { it.title == "Bash Tru" }

        event.artists.map { it.name to it.role } shouldBe
            listOf(
                "Bash Tru" to "HEADLINER",
                "DJ Lobotomy" to "DJ"
            )
    }

    @Test
    fun `treats a bandless banner row as a non-concert event with no headliner artist`() {
        // The flea-market row ("So 06.09.") has a `.headlines` banner but no `.band`.
        val html =
            """
            <html><body><table>
              <tr>
                <td><span class="datum">So<br>06.09.</span></td>
                <td>
                  <p class="headlines">Punk &amp; Rock`n`Roll Flohmarkt. Drinnen und Draussen ab 14 Uhr</p>
                </td>
                <td><p><img src="/uploads/img/flohmarkt.jpg"></p></td>
              </tr>
            </table></body></html>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()
        event.title shouldBe "Punk & Rock`n`Roll Flohmarkt. Drinnen und Draussen ab 14 Uhr"
        // "Flohmarkt" (contains "markt") is not a concert, and its title is an event name, not a performer.
        event.eventType shouldBe "OTHER"
        event.artists shouldHaveSize 0
        // The banner is the title, so it is not repeated as a subtitle.
        event.subtitle.shouldBeNull()
        // "ab 14 Uhr" is the only time the listing states for it (#1142).
        event.startTime shouldBe LocalTime.of(14, 0)
    }

    @Test
    fun `reads an ab-Uhr banner time and leaves a row with no published time timeless`() {
        // The 5 September 2026 programme: the flea market says "ab 14 Uhr"; Les Calcatoggios states no time at all.
        val autumnClock = Clock.fixed(Instant.parse("2026-09-04T10:00:00Z"), ZoneOffset.UTC)
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/wildatheart/wildatheart-concerts-autumn.html")!!
                .bufferedReader()
                .readText()
        val events = WildAtHeartOverviewPageScraper(autumnClock).scrape(Jsoup.parse(html, baseUrl), baseUrl)

        events.first { it.title.startsWith("Punk & Rock`n`Roll Flohmarkt") }.startTime shouldBe LocalTime.of(14, 0)
        val calcatoggios = events.first { it.title == "Les Calcatoggios" }
        calcatoggios.startTime.shouldBeNull()
        calcatoggios.doorsTime.shouldBeNull()
    }

    @Test
    fun `returns no events when the page has no dated rows`() {
        val html = "<html><body><table><tr><td>No events yet</td></tr></table></body></html>"
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl) shouldHaveSize 0
    }
}
