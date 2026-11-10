package de.norm.events.scraper.tempodrom

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [TempodromDetailPageScraper], against saved event pages. */
class TempodromDetailPageScraperTest {
    private val scraper = TempodromDetailPageScraper()

    private val listingRow =
        ScrapedEvent(
            title = "Jill Scott",
            eventDate = LocalDate.of(2026, 10, 6),
            sourceUrl = "https://www.tempodrom.de/event/jill_scott_2026-10-06_20/",
            sourceId = "tempodrom:jill_scott_2026-10-06_20"
        )

    private val carrackRow =
        listingRow.copy(
            title = "Paul Carrack",
            subtitle = "verlegt ins Huxley's neue Welt",
            eventDate = LocalDate.of(2026, 10, 18),
            sourceUrl = "https://www.tempodrom.de/event/paul_carrack_2026-10-18_19/",
            sourceId = "tempodrom:paul_carrack_2026-10-18_19",
            status = "CANCELLED"
        )

    private fun fallbackRow(
        title: String,
        subtitle: String?
    ) = listingRow.copy(title = title, subtitle = subtitle, eventType = "CONCERT", typeIsFallback = true)

    private fun descriptionPage(blurb: String) =
        Jsoup.parse("""<button class="acc-button btn" aria-controls="event-txt-1">Beschreibung <span></span></button><div id="event-txt-1"><p>$blurb</div>""")

    private val pageUrl = listingRow.sourceUrl
    private val trinityHtml = "tempodrom/tempodrom-detail-trinity-music.html"
    private val d2mberlinHtml = "tempodrom/tempodrom-detail-d2mberlin.html"
    private val shadenHtml = "tempodrom/tempodrom-detail-shaden-comedy.html"
    private val comedyPromoterHtml = "tempodrom/tempodrom-detail-comedy-promoter-no-cue.html"
    private val carrackHtml = "tempodrom/tempodrom-detail-paul-carrack-relocated.html"
    private val tributeHtml = "tempodrom/tempodrom-detail-tribute-band-in-blurb.html"
    private val roncalliHtml = "tempodrom/tempodrom-detail-roncalli-schedule-table.html"
    private val holidayOnIceHtml = "tempodrom/tempodrom-detail-holiday-on-ice-schedule.html"

    private val roncalliRow =
        listingRow.copy(
            title = "Roncalli Weihnachtscircus 2026/2027 - 22. Original Roncalli Weihnachtscircus im Tempodrom Berlin",
            eventDate = LocalDate.of(2026, 12, 17),
            endDate = LocalDate.of(2027, 1, 3),
            sourceUrl = "https://www.tempodrom.de/event/roncalli_weihnachtscircus_2026_2027_2026-12-17_2027-01-03_00/",
            sourceId = "tempodrom:roncalli_weihnachtscircus_2026_2027_2026-12-17_2027-01-03_00"
        )

    private val holidayOnIceRow =
        listingRow.copy(
            title = "Holiday on Ice",
            eventDate = LocalDate.of(2027, 2, 26),
            endDate = LocalDate.of(2027, 3, 14),
            sourceUrl = "https://www.tempodrom.de/event/holiday_on_ice_2027-02-26_2027-03-14_00/",
            sourceId = "tempodrom:holiday_on_ice_2027-02-26_2027-03-14_00"
        )

    @Test
    fun `reads the promoter and its website from the Veranstalter credit`() {
        val event = scraper.addPromoter(listingRow, fixtureDocument(trinityHtml, pageUrl))

        event.promoters shouldBe listOf("Trinity Music GmbH")
        event.promoterWebsites shouldBe mapOf("Trinity Music GmbH" to "http://www.trinitymusic.de")
    }

    @Test
    fun `reads a second promoter from another page`() {
        val event = scraper.addPromoter(listingRow, fixtureDocument(d2mberlinHtml, pageUrl))

        event.promoters shouldBe listOf("d2mberlin GmbH")
        event.promoterWebsites shouldBe mapOf("d2mberlin GmbH" to "http://www.d2mberlin.de")
    }

    @Test
    fun `never reads the media partner the blurb names`() {
        // The Jill Scott page says "präsentiert von Radio Eins" in its blurb (#2653).
        scraper.addPromoter(listingRow, fixtureDocument(trinityHtml, pageUrl)).promoters shouldBe listOf("Trinity Music GmbH")
    }

    @Test
    fun `keeps a credit without a link and drops its label`() {
        val document = Jsoup.parse("<div class=copy>Veranstalter: Tempodrom GmbH</div>", listingRow.sourceUrl)

        val event = scraper.addPromoter(listingRow, document)

        event.promoters shouldBe listOf("Tempodrom GmbH")
        event.promoterWebsites shouldBe emptyMap()
    }

    @Test
    fun `repairs a name the venue stores double-encoded and drops an empty link`() {
        // elena_uhlig_und_fritz_karl_2027-04-01_20 credits it exactly so.
        val document = Jsoup.parse("<div class=copy>Veranstalter <a href=http:// target=_blank>KonzertbÃ¼ro Augsburg</a></div>", listingRow.sourceUrl)

        val event = scraper.addPromoter(listingRow, document)

        event.promoters shouldBe listOf("Konzertbüro Augsburg")
        event.promoterWebsites shouldBe emptyMap()
    }

    @Test
    fun `leaves a correctly encoded name alone`() {
        val document = Jsoup.parse("<div class=copy>Veranstalter <a>Concertbüro Zahlmann GmbH</a></div>", listingRow.sourceUrl)

        scraper.addPromoter(listingRow, document).promoters shouldBe listOf("Concertbüro Zahlmann GmbH")
    }

    @Test
    fun `types a fallback night COMEDY when the Beschreibung names a comedian`() {
        // "The iconic standup comedian Shaden premieres her … brand new special" (#3380).
        val event = scraper.enrich(fallbackRow("SHADEN (أنا إمرأة تعبانة) Show", subtitle = null), fixtureDocument(shadenHtml, pageUrl))

        event.eventType shouldBe "COMEDY"
        event.typeIsFallback shouldBe false
        event.promoters shouldBe listOf("Levanto Entertainment")
    }

    @Test
    fun `keeps a fallback CONCERT whose only comedy is the promoter's name`() {
        // Abishek Kumar's page credits "The Comedy Cornershop" and its blurb names no comedy.
        val row = fallbackRow("Abishek Kumar", "Abishek wants to Enjoy Life! - Europe/UK tour 2026")

        val event = scraper.enrich(row, fixtureDocument(comedyPromoterHtml, pageUrl))

        event.eventType shouldBe "CONCERT"
        event.typeIsFallback shouldBe true
        event.promoters shouldBe listOf("The Comedy Cornershop")
    }

    @Test
    fun `reads stand-up, comedians and Kabarett as comedy cues`() {
        listOf(
            "one of India's most critically acclaimed stand-up comedians",
            "Ein Abend voller Comedy",
            "die Kabarettistin aus Leipzig",
            "Politisches Kabarett"
        ).forEach { blurb ->
            scraper.addComedyType(fallbackRow("Act", null), descriptionPage(blurb)).eventType shouldBe "COMEDY"
        }
    }

    @Test
    fun `reads a comedy cue in the title or the format line when the page has no Beschreibung`() {
        val event = scraper.addComedyType(fallbackRow("Act", "Kabarett 2027"), Jsoup.parse("<div class=copy>Event teilen</div>"))

        event.eventType shouldBe "COMEDY"
    }

    @Test
    fun `reads no cue from the house rules or from a word that only contains one`() {
        val page =
            Jsoup.parse(
                """
                <button class="acc-button" aria-controls="event-txt-1">Beschreibung</button>
                <div id="event-txt-1">Die Band spielt ihre Hits. Comedyfans und Standuptische gibt es nicht.</div>
                <button class="acc-button" aria-controls="event-txt-2">Hinweis zur Einlasskontrolle</button>
                <div id="event-txt-2">Kein Einlass mit Comedy-Requisiten.</div>
                """.trimIndent()
            )

        scraper.addComedyType(fallbackRow("Band", null), page).eventType shouldBe "CONCERT"
    }

    @Test
    fun `never retypes a night the listing typed from a cue`() {
        val show = fallbackRow("Ice Show", null).copy(eventType = "SHOW", typeIsFallback = false)

        scraper.addComedyType(show, descriptionPage("A comedy on ice")) shouldBeSameInstanceAs show
    }

    @Test
    fun `reads the verlegt badge as RELOCATED over the JSON-LD's EventCancelled`() {
        // paul_carrack_2026-10-18_19: JSON-LD `EventCancelled`, the page `big-btn relocated` (#3576).
        val event = scraper.enrich(carrackRow, fixtureDocument(carrackHtml, pageUrl))

        event.status shouldBe "RELOCATED"
    }

    @Test
    fun `stores the house the subtitle names as relocatedTo`() {
        val entity =
            scraper
                .enrich(carrackRow, fixtureDocument(carrackHtml, pageUrl))
                .toEventEntity(venueId = 1L, venueSlug = "tempodrom", eventSourceId = 1L)

        entity.status shouldBe "RELOCATED"
        entity.relocatedTo shouldBe "Huxley's neue Welt"
    }

    @Test
    fun `keeps the listing's status on a page with a ticket button`() {
        listOf("tempodrom-detail-trinity-music.html", "tempodrom-detail-d2mberlin.html").forEach { name ->
            listOf("SCHEDULED", "CANCELLED", "POSTPONED").forEach { status ->
                val row = listingRow.copy(status = status)
                scraper.enrich(row, fixtureDocument("tempodrom/$name", pageUrl)).status shouldBe status
            }
        }
    }

    private fun tributeRow(day: Int) =
        listingRow.copy(
            title = "NIGHTS ON BROADWAY: Anniversary Tour '27",
            subtitle = "60 Jahre BEE GEES - 20 Jahre BEE GEES Tribute",
            eventDate = LocalDate.of(2027, 10, day),
            sourceUrl = "https://www.tempodrom.de/event/nights_on_broadway-anniversary_tour-27_2027-10-0${day}_20/",
            sourceId = "tempodrom:nights_on_broadway-anniversary_tour-27_2027-10-0${day}_20",
            artists = listOf(ScrapedArtist("Nights On Broadway: Anniversary Tour '27", ArtistRole.HEADLINER))
        )

    @Test
    fun `bills the tribute band the blurb names when the format line names none`() {
        // Both 2027 nights print "NIGHT FEVER gilt als die erfolgreichste … BEE GEES Tribute-Band" (#3828).
        listOf(6, 7).forEach { day ->
            val event = scraper.enrich(tributeRow(day), fixtureDocument(tributeHtml, pageUrl))

            event.artists shouldBe listOf(ScrapedArtist("Night Fever", ArtistRole.HEADLINER))
            event.title shouldBe "NIGHTS ON BROADWAY: Anniversary Tour '27"
            event.promoters shouldBe listOf("Concertbüro Zahlmann GmbH")
        }
    }

    @Test
    fun `keeps the band a performed-by line names`() {
        val row =
            tributeRow(6).copy(
                subtitle = "A Tribute to the BEE GEES performed by NIGHT FEVER",
                artists = listOf(ScrapedArtist("Night Fever", ArtistRole.HEADLINER))
            )

        scraper.addTributeBand(row, descriptionPage("THE BROTHERS gilt als die beste Tribute-Band")) shouldBeSameInstanceAs row
    }

    @Test
    fun `keeps the listing's artists when the blurb names no tribute band`() {
        listOf(
            "Weitere Infos folgen.",
            "Die Band gilt als die beste Tribute-Band Europas.",
            "NIGHT FEVER gilt als Legende. Die Tribute-Band spielt alle Hits."
        ).forEach { blurb ->
            val row = tributeRow(6)
            scraper.addTributeBand(row, descriptionPage(blurb)) shouldBeSameInstanceAs row
        }
    }

    private fun wahnsinnRow(subtitle: String) =
        listingRow.copy(
            title = "WAHNSINN!",
            subtitle = subtitle,
            eventDate = LocalDate.of(2027, 2, 21),
            sourceUrl = "https://www.tempodrom.de/event/wahnsinn-2027-02-21_19/",
            sourceId = "tempodrom:wahnsinn-2027-02-21_19",
            artists = emptyList()
        )

    @Test
    fun `keeps no act for a show of another artist's songs whose blurb names no band`() {
        // Both Wahnsinn blurbs name only "Eine herausragende Band und großartige Solisten" (#4341).
        listOf(
            "Die Show - Die beste Wolfgang Petry Party geht weiter - Tour 2026",
            "Die Show 2027 - Die Jubiläumstournee - Mit den Hits von Wolfgang Petry"
        ).forEach { subtitle ->
            val row = wahnsinnRow(subtitle)
            val blurb =
                "Da die Musiklegende selbst schon lange nicht mehr live auftritt, soll den Fans damit dennoch die Möglichkeit gegeben werden. " +
                    "Eine herausragende Band und großartige Solisten bringen das Petry-Feeling authentisch auf die Bühne."

            scraper.addTributeBand(row, descriptionPage(blurb)).artists shouldBe emptyList()
        }
    }

    @Test
    fun `bills the band the blurb names for a show of another artist's songs`() {
        val event =
            scraper.addTributeBand(
                wahnsinnRow("Die Show 2027 - Mit den Hits von Wolfgang Petry"),
                descriptionPage("DIE WOLLE BAND gilt als die beste Wolfgang Petry Tribute-Band.")
            )

        event.artists shouldBe listOf(ScrapedArtist("Die Wolle Band", ArtistRole.HEADLINER))
    }

    @Test
    fun `reads no tribute band for a night whose format line bills no tribute`() {
        val row = listingRow.copy(subtitle = "Live 2027", artists = listOf(ScrapedArtist("Jill Scott", ArtistRole.HEADLINER)))

        scraper.addTributeBand(row, descriptionPage("NIGHT FEVER gilt als die beste Tribute-Band")) shouldBeSameInstanceAs row
    }

    @Test
    fun `reads a long band name with digits, an ampersand and an apostrophe`() {
        val event =
            scraper.addTributeBand(
                tributeRow(7),
                descriptionPage("Seit 20 Jahren auf Tour: THE 4 BROTHERS & SISTER'S SPECIAL GUEST BAND gilt als die beste ABBA Tribute Band.")
            )

        event.artists.map { it.name } shouldBe listOf("The 4 Brothers & Sister's Special Guest Band")
    }

    @Test
    fun `starts a run at the first show its schedule table lists on the run's first day`() {
        // Roncalli's table: `Do. | 17.12.2026 | 18:00 Uhr` (#4821).
        val event = scraper.enrich(roncalliRow, fixtureDocument(roncalliHtml, roncalliRow.sourceUrl))

        event.startTime shouldBe LocalTime.of(18, 0)
        event.eventDate shouldBe LocalDate.of(2026, 12, 17)
        event.endDate shouldBe LocalDate.of(2027, 1, 3)
        event.title shouldBe roncalliRow.title
        event.status shouldBe "SCHEDULED"
    }

    @Test
    fun `starts a run at the first of the shows on its first day`() {
        // Holiday on Ice: `Freitag | 26.02.2027 | 16:00 Uhr + 19:30 Uhr`.
        val event = scraper.enrich(holidayOnIceRow, fixtureDocument(holidayOnIceHtml, holidayOnIceRow.sourceUrl))

        event.startTime shouldBe LocalTime.of(16, 0)
    }

    @Test
    fun `reads the first clock of a two-show day and a day after the year end`() {
        val page = fixtureDocument(roncalliHtml, roncalliRow.sourceUrl)

        mapOf(
            LocalDate.of(2026, 12, 18) to LocalTime.of(15, 0),
            LocalDate.of(2026, 12, 22) to LocalTime.of(15, 0),
            LocalDate.of(2027, 1, 1) to LocalTime.of(13, 0)
        ).forEach { (day, start) ->
            scraper.addScheduleStart(roncalliRow.copy(eventDate = day), page).startTime shouldBe start
        }
    }

    @Test
    fun `reads the schedule as prose`() {
        val page = descriptionPage("Spielplan 2027 mit Beginnzeiten Freitag 26.02.2027 16:00 Uhr + 19:30 Uhr Samstag 27.02.2027 13:00 Uhr + 16:30 Uhr")

        scraper.addScheduleStart(holidayOnIceRow, page).startTime shouldBe LocalTime.of(16, 0)
        scraper.addScheduleStart(holidayOnIceRow.copy(eventDate = LocalDate.of(2027, 2, 27)), page).startTime shouldBe LocalTime.of(13, 0)
    }

    @Test
    fun `stores no start when the Beschreibung lists no show on the event's day`() {
        val dayBefore = roncalliRow.copy(eventDate = LocalDate.of(2026, 12, 16))
        scraper.addScheduleStart(dayBefore, fixtureDocument(roncalliHtml, roncalliRow.sourceUrl)) shouldBeSameInstanceAs dayBefore
        listOf(
            fixtureDocument(trinityHtml, pageUrl),
            descriptionPage("Vorverkauf ab 17.12.2026, 10 Uhr. Einlass am 17.12.2026 ab 2 Stunden vorher."),
            Jsoup.parse("<div class=copy>Event teilen</div><table><tr><td>Do.<td>17.12.2026<td>18:00 Uhr</table>")
        ).forEach { page ->
            scraper.addScheduleStart(roncalliRow, page) shouldBeSameInstanceAs roncalliRow
        }
        scraper.enrich(listingRow, fixtureDocument(trinityHtml, pageUrl)).startTime shouldBe null
    }

    @Test
    fun `keeps a start the listing stated`() {
        val row = roncalliRow.copy(startTime = LocalTime.of(19, 30))

        scraper.addScheduleStart(row, fixtureDocument(roncalliHtml, roncalliRow.sourceUrl)) shouldBeSameInstanceAs row
    }

    @Test
    fun `keeps every status beside a run's start, and a move outranks the listing's`() {
        val schedule = """<button class="acc-button" aria-controls="event-txt-1">Beschreibung</button><div id="event-txt-1">Do. 17.12.2026 18:00 Uhr</div>"""
        listOf("SCHEDULED", "CANCELLED", "POSTPONED", "RELOCATED").forEach { status ->
            val event = scraper.enrich(roncalliRow.copy(status = status), Jsoup.parse(schedule))

            event.status shouldBe status
            event.startTime shouldBe LocalTime.of(18, 0)
        }
        val moved = scraper.enrich(roncalliRow.copy(status = "CANCELLED"), Jsoup.parse("""$schedule<a class="btn big-btn relocated">verlegt</a>"""))
        moved.status shouldBe "RELOCATED"
        moved.startTime shouldBe LocalTime.of(18, 0)
    }

    @Test
    fun `leaves the event as the listing gave it when the page credits no promoter`() {
        val document = Jsoup.parse("<div class=copy>Event teilen</div><p>präsentiert von Radio Eins</p>", listingRow.sourceUrl)

        scraper.addPromoter(listingRow, document) shouldBeSameInstanceAs listingRow
    }
}
