package de.norm.events.scraper.monarch

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MonarchOverviewPageScraper].
 *
 * Parses the real retro `programm.php` snapshot plus synthetic fragments for the
 * cancellation and ticket-link edge cases the live page did not carry at capture
 * time. Dates carry a full year, so no clock is needed; the scraper returns every
 * dated block as-is (dropping past events is the persistence layer's concern).
 */
class MonarchOverviewPageScraperTest {
    private val baseUrl = "https://kottimonarch.de/programm.php"
    private val scraper = MonarchOverviewPageScraper()

    private fun programme() =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/monarch/monarch-overview.html")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    private fun block(
        dateLine: String,
        title: String
    ) = Jsoup.parse(
        """
        <html><body>
          <div class="nachrichten">
            <div style="border-bottom:1px solid #cccccc; padding-left:4px;">
              <b>$dateLine</b><br>
              <table><tr><td id=td1><b><div>$title</div></b></td></tr></table>
            </div>
          </div>
        </body></html>
        """.trimIndent(),
        baseUrl
    )

    @Test
    fun `parses every event block from the retro programme page`() {
        scraper.scrape(programme(), baseUrl) shouldHaveSize 18
    }

    @Test
    fun `extracts a fully-populated concert without a ticket link`() {
        val event = scraper.scrape(programme(), baseUrl).first()

        // "SAINT PERRY PEAN (KONZERT)" → title stripped of the concert marker.
        event.title shouldBe "SAINT PERRY PEAN"
        event.eventType shouldBe "CONCERT"
        event.eventDate shouldBe LocalDate.of(2026, 7, 11)
        event.startTime shouldBe LocalTime.of(18, 30)
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "monarch:2026-07-11-saint-perry-pean"
        event.status shouldBe "SCHEDULED"
        // No "Ticket Vorverkauf" link → this event is pay-at-door ("Abendkasse").
        event.ticketUrl.shouldBeNull()
        // A concert's title names the headliner.
        event.artists shouldHaveSize 1
        event.artists.single().name shouldBe "SAINT PERRY PEAN"
        event.artists.single().role shouldBe "HEADLINER"
    }

    @Test
    fun `extracts the ticket link for a concert that has one`() {
        val byrd =
            scraper
                .scrape(programme(), baseUrl)
                .single { it.title == "M. BYRD" }

        byrd.eventType shouldBe "CONCERT"
        byrd.eventDate shouldBe LocalDate.of(2026, 9, 4)
        byrd.ticketUrl.shouldStartWith("https://dice.fm/partner/tickets/event/")
        byrd.artists.single().name shouldBe "M. BYRD"
    }

    @Test
    fun `treats a non-concert club night with no keyword cue as OTHER with no artists`() {
        val offBeat =
            scraper
                .scrape(programme(), baseUrl)
                .single { it.title == "OFF BEAT: SUMMER SESSIONS" }

        // No "(KONZERT)" marker and no party/quiz/… keyword in the title → OTHER
        // (never CONCERT, so the event name is not minted as a headliner).
        offBeat.eventType shouldBe "OTHER"
        offBeat.startTime shouldBe LocalTime.of(23, 0)
        offBeat.ticketUrl shouldBe "https://ra.co/events/2475168"
        // A party/DJ-night title is not an artist.
        offBeat.artists shouldHaveSize 0
    }

    @Test
    fun `picks the external ticket shop over an artist info link in the lineup cell`() {
        // "DAS LUNSENTRIO" has both a "Ticket Vorverkauf" link (vvk.link) and an
        // artist website icon link (tapeterecords.de) inside its td.tom cell.
        val event =
            scraper
                .scrape(programme(), baseUrl)
                .single { it.title == "DAS LUNSENTRIO" }

        event.ticketUrl shouldBe "https://vvk.link/xa4sd"
    }

    @Test
    fun `flags a cancelled concert and strips the ABGESAGT marker from the title`() {
        val html =
            """
            <html><body>
              <div class="nachrichten">
                <div style="border-bottom:1px solid #cccccc; padding-left:4px;">
                  <b>Freitag 24/07/2026-20:00</b><br>
                  <table><tr><td id=td1><b><div>ABGESAGT WEIRD YOUTH (KONZERT) </div></b></td></tr></table>
                  <table><tr><td id='tom' class='tom'>Abendkasse</td></tr></table>
                </div>
              </div>
            </body></html>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        event.title shouldBe "WEIRD YOUTH"
        event.status shouldBe "CANCELLED"
        event.eventType shouldBe "CONCERT"
        event.eventDate shouldBe LocalDate.of(2026, 7, 24)
        event.sourceId shouldBe "monarch:2026-07-24-weird-youth"
    }

    @Test
    fun `keeps an event whose date line has no time yet, with no start time`() {
        val event = scraper.scrape(block("Samstag 24/10/2026-", "CHEERS QUEERS"), baseUrl).single()

        event.title shouldBe "CHEERS QUEERS"
        event.eventDate shouldBe LocalDate.of(2026, 10, 24)
        event.startTime.shouldBeNull()
        event.sourceId shouldBe "monarch:2026-10-24-cheers-queers"
    }

    @Test
    fun `skips a block whose date line carries no date`() {
        scraper.scrape(block("Samstag", "CHEERS QUEERS"), baseUrl) shouldHaveSize 0
    }

    @Test
    fun `returns no events when the page has no event blocks`() {
        val html = "<html><body><p>Monarch – no programme yet</p></body></html>"
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl) shouldHaveSize 0
    }
}
