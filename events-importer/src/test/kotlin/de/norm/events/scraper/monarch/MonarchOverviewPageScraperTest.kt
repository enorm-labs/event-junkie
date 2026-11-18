package de.norm.events.scraper.monarch

import de.norm.events.artist.canonicalArtistName
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.withWeekdayWarnings
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
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

    /** The live 2026-10-24 shape: `td#td1` blank, the name in the second line under `Veranstalter*in` (#2045). */
    private fun organiserBlock(
        dateLine: String,
        organiser: String
    ) = Jsoup.parse(
        """
        <html><body>
          <div style='border-bottom:1px solid #cccccc; padding-left:4px; font-size:14px;'><b>$dateLine</b><br>
            <a href=https://hausofus.ditix.shop/event/66p0v12m0t41xn3m target=_blank>Ticket Vorverkauf</a><br>
            <table><tr><td id=td1><b><div > </div></b></td></tr><tr><td><b><div >$organiser</div></b></td></tr><tr><td class=kotti_online_td>Veranstalter*in</td></tr></table>
          </div>
        </body></html>
        """.trimIndent(),
        baseUrl
    )

    @Test
    fun `parses every event block from the retro programme page`() {
        scraper.scrape(fixtureDocument("monarch/monarch-overview.html", baseUrl), baseUrl) shouldHaveSize 18
    }

    @Test
    fun `extracts a fully-populated concert without a ticket link`() {
        val event = scraper.scrape(fixtureDocument("monarch/monarch-overview.html", baseUrl), baseUrl).first()

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
        event.artists.single().role shouldBe ArtistRole.HEADLINER
    }

    @Test
    fun `extracts the ticket link for a concert that has one`() {
        val byrd =
            scraper
                .scrape(fixtureDocument("monarch/monarch-overview.html", baseUrl), baseUrl)
                .single { it.title == "M. BYRD" }

        byrd.eventType shouldBe "CONCERT"
        byrd.eventDate shouldBe LocalDate.of(2026, 9, 4)
        byrd.ticketUrl.shouldStartWith("https://dice.fm/partner/tickets/event/")
        byrd.artists.single().name shouldBe "M. BYRD"
    }

    @Test
    fun `types an unmarked night that starts at nine or later as a club night with no artists`() {
        val events = scraper.scrape(fixtureDocument("monarch/monarch-overview.html", baseUrl), baseUrl)
        val offBeat = events.single { it.title == "OFF BEAT: SUMMER SESSIONS" }

        // No "(KONZERT)" marker and no keyword in the title, but a 23:00 start: a club night,
        // never a CONCERT, so the event name is not minted as a headliner.
        offBeat.eventType shouldBe "PARTY"
        events.filter { it.title == "INDIE GROUND" }.map { it.eventType }.toSet() shouldBe setOf("PARTY")
        events.single { it.title == "CHEERS QUEERS – CSD EDITION" }.eventType shouldBe "PARTY"
        // An unmarked 19:00 night gives no club-night signal.
        events.single { it.title == "DAS LUNSENTRIO" }.eventType shouldBe "OTHER"
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
                .scrape(fixtureDocument("monarch/monarch-overview.html", baseUrl), baseUrl)
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
    fun `strips an ABGESAGT marker with its separator and keeps the headliner`() {
        // The live 2026-11-14 cell (#4981).
        val event = scraper.scrape(block("Samstag 14/11/2026-20:00", "ABGESAGT - REALLY GOOD TIME (KONZERT)"), baseUrl).single()

        event.title shouldBe "REALLY GOOD TIME"
        event.status shouldBe "CANCELLED"
        event.eventType shouldBe "CONCERT"
        event.sourceId shouldBe "monarch:2026-11-14-really-good-time"
        event.artists.single().name shouldBe "REALLY GOOD TIME"
        event.artists.single().role shouldBe ArtistRole.HEADLINER
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ABGESAGT: REALLY GOOD TIME (KONZERT)",
            "ABGESAGT – REALLY GOOD TIME (KONZERT)",
            "ABGESAGT! REALLY GOOD TIME (KONZERT)",
            "(ABGESAGT) REALLY GOOD TIME (KONZERT)",
            "abgesagt -REALLY GOOD TIME (KONZERT)",
            "REALLY GOOD TIME - ABGESAGT (KONZERT)",
            "REALLY GOOD TIME (KONZERT) ABGESAGT"
        ]
    )
    fun `strips every separator form of the ABGESAGT marker`(cell: String) {
        val event = scraper.scrape(block("Samstag 14/11/2026-20:00", cell), baseUrl).single()

        event.title shouldBe "REALLY GOOD TIME"
        event.status shouldBe "CANCELLED"
    }

    @Test
    fun `keeps a long cancelled title whole after its marker`() {
        val title = "REALLY GOOD TIME - THE FAREWELL TOUR WITH VERY SPECIAL GUESTS FROM HAMBURG AND A LATE AFTERSHOW IN THE BAR"
        val event = scraper.scrape(block("Samstag 14/11/2026-20:00", "ABGESAGT - $title (KONZERT)"), baseUrl).single()

        event.title shouldBe title
        event.status shouldBe "CANCELLED"
    }

    @Test
    fun `keeps an event whose date line has no time yet and whose name is only on the organiser line`() {
        val event = scraper.scrape(organiserBlock("Samstag 24/10/2026-", "CHEERS QUEERS"), baseUrl).single()

        event.title shouldBe "CHEERS QUEERS"
        event.eventDate shouldBe LocalDate.of(2026, 10, 24)
        event.startTime.shouldBeNull()
        event.sourceId shouldBe "monarch:2026-10-24-cheers-queers"
        event.ticketUrl shouldBe "https://hausofus.ditix.shop/event/66p0v12m0t41xn3m"
    }

    @Test
    fun `types a night with no time as a club night when its blurb calls it a party`() {
        val html =
            organiserBlock("Samstag 24/10/2026-", "CHEERS QUEERS").outerHtml().replace(
                "</table>",
                "</table><table><tr><td id='tom' class='tom'>Deine queere Pop-Party geht in die fünfte Runde. " +
                    "Are you ready for Drag, Drinks &amp; Dancefloor?</td></tr></table>"
            )
        val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        event.eventType shouldBe "PARTY"
        event.artists shouldHaveSize 0
    }

    @Test
    fun `skips a block whose title and organiser lines are both blank`() {
        scraper.scrape(organiserBlock("Samstag 24/10/2026-19:00", " "), baseUrl) shouldHaveSize 0
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

    @Test
    fun `moves an event whose weekday names the neighbouring month`() {
        // 3 September 2026 is a Thursday; the Saturday the heading names is 3 October.
        val (events, warnings) = withWeekdayWarnings { scraper.scrape(block("Samstag 03/09/2026-20:00", "Klubnacht"), baseUrl) }

        events.single().eventDate shouldBe LocalDate.of(2026, 10, 3)
        events.single().sourceId shouldBe "monarch:2026-10-03-klubnacht"
        warnings shouldHaveSize 1
    }

    @Test
    fun `a numbered session is a concert that bills nobody`() {
        val event = scraper.scrape(block("Freitag 13/11/2026-20:00", "LEAKED SESSION 04 (KONZERT)"), baseUrl).single()

        event.title shouldBe "LEAKED SESSION 04"
        event.eventType shouldBe "CONCERT"
        event.artists shouldHaveSize 0
    }

    @Test
    fun `an act with Session in its name stays billed`() {
        fun billed(title: String) =
            scraper
                .scrape(block("Freitag 13/11/2026-20:00", "$title (KONZERT)"), baseUrl)
                .single()
                .artists
                .map { it.name }

        billed("Session Victim") shouldBe listOf("Session Victim")
        billed("Berlin Session Strings") shouldBe listOf("Berlin Session Strings")
    }

    @Test
    fun `capitalises a small umlaut inside a shouted title, so the headliner de-shouts`() {
        // The live 2026-11-14 cell (#4996).
        val event = scraper.scrape(block("Freitag 11/12/2026-20:00", "KEIN GEWöHNLICHER STURM (KONZERT)"), baseUrl).single()

        event.title shouldBe "KEIN GEWÖHNLICHER STURM"
        event.sourceId shouldBe "monarch:2026-12-11-kein-gewohnlicher-sturm"
        canonicalArtistName(event.artists.single().name) shouldBe "Kein Gewöhnlicher Sturm"
        event.artists.single().role shouldBe ArtistRole.HEADLINER
    }

    @Test
    fun `capitalises a small umlaut in a shouted billing of several acts`() {
        val event =
            scraper.scrape(block("Samstag 28/11/2026-20:00", "HERZER & GEBRüDER MANNS & THE HAND CUTS (KONZERT)"), baseUrl).single()

        event.title shouldBe "HERZER & GEBRÜDER MANNS & THE HAND CUTS"
        event.sourceId shouldBe "monarch:2026-11-28-herzer-gebruder-manns-the-hand-cuts"
        // The scraper's split is unchanged; only `GEBRüDER` no longer keeps the second billing shouted.
        event.artists.map { canonicalArtistName(it.name) } shouldBe listOf("Herzer", "Gebrüder Manns & The Hand Cuts")
    }

    @Test
    fun `bills an unmarked title of three dashed acts as a concert (event 14654)`() {
        val event =
            scraper.scrape(block("Samstag 28/11/2026-18:00", "THE HAND CUTS - GÜNTHER AND THE JAUCHS - BLUTCYPHER"), baseUrl).single()

        event.eventType shouldBe EventType.CONCERT.name
        event.artists.map { canonicalArtistName(it.name) } shouldBe listOf("The Hand Cuts", "Günther And The Jauchs", "Blutcypher")
        event.artists.map { it.titleDerived }.distinct() shouldBe listOf(true)
    }

    @Test
    fun `bills a night's name dashed to one act playing LIVE as a concert (event 14655)`() {
        val event = scraper.scrape(block("Donnerstag 03/12/2026-20:00", "CITY SLANG XMAS - PARTY DOZEN LIVE"), baseUrl).single()

        event.eventType shouldBe EventType.CONCERT.name
        event.artists.map { canonicalArtistName(it.name) } shouldBe listOf("Party Dozen")
        event.artists.single().role shouldBe ArtistRole.HEADLINER
        event.artists.single().titleDerived shouldBe true
    }

    @Test
    fun `keeps a two-part dashed title a night's name`() {
        val event = scraper.scrape(block("Samstag 18/07/2026-18:00", "CHEERS QUEERS – CSD EDITION"), baseUrl).single()

        event.eventType shouldNotBe EventType.CONCERT.name
        event.artists shouldBe emptyList()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "BLüMCHEN|BLÜMCHEN",
            "DJ Fü|DJ FÜ",
            "MüLLER, MäDCHEN & SöHNE|MÜLLER, MÄDCHEN & SÖHNE",
            "Gebrüder Manns|Gebrüder Manns",
            "Die Ärzte über Köln|Die Ärzte über Köln",
            "STRAßE DER LIEDER|STRAßE DER LIEDER",
            "GROßE äRA|GROßE äRA"
        ]
    )
    fun `capitalises only an umlaut that has a capital before it`(
        cell: String,
        title: String
    ) {
        scraper.scrape(block("Samstag 14/11/2026-20:00", cell), baseUrl).single().title shouldBe title
    }

    @Test
    fun `capitalises small umlauts throughout a long title`() {
        val event =
            scraper
                .scrape(
                    block(
                        "Samstag 14/11/2026-20:00",
                        "KEIN GEWöHNLICHER STURM - DIE ABSCHIEDSTOUR MIT GäSTEN AUS MüNCHEN UND EINER AFTERSHOW IN DER BAR (KONZERT)"
                    ),
                    baseUrl
                ).single()

        event.title shouldBe "KEIN GEWÖHNLICHER STURM - DIE ABSCHIEDSTOUR MIT GÄSTEN AUS MÜNCHEN UND EINER AFTERSHOW IN DER BAR"
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "ABGESAGT GEWöHNLICHER STURM (KONZERT)|CANCELLED",
            "ABGESAGT - GEWöHNLICHER STURM (KONZERT)|CANCELLED",
            "GEWöHNLICHER STURM - ABGESAGT (KONZERT)|CANCELLED",
            "ABSAGE: GEWöHNLICHER STURM (KONZERT)|CANCELLED",
            "CANCELLED: GEWöHNLICHER STURM (KONZERT)|CANCELLED",
            "VERSCHOBEN: GEWöHNLICHER STURM (KONZERT)|POSTPONED",
            "POSTPONED: GEWöHNLICHER STURM (KONZERT)|POSTPONED",
            "WIRD VERLEGT: GEWöHNLICHER STURM (KONZERT)|RELOCATED",
            "VENUE äNDERUNG: GEWöHNLICHER STURM (KONZERT)|RELOCATED",
            "VENUE ÄNDERUNG: GEWöHNLICHER STURM (KONZERT)|RELOCATED"
        ]
    )
    fun `reads the status and strips its marker beside a small umlaut`(
        cell: String,
        status: String
    ) {
        val event = scraper.scrape(block("Samstag 14/11/2026-20:00", cell), baseUrl).single()

        event.title shouldBe "GEWÖHNLICHER STURM"
        event.status shouldBe status
        canonicalArtistName(event.artists.single().name) shouldBe "Gewöhnlicher Sturm"
    }
}
