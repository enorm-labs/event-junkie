package de.norm.events.scraper.tempodrom

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [TempodromOverviewPageScraper].
 *
 * Uses a real snapshot of the programme page, whose JSON-LD carries the venue's entire programme.
 * Several assertions exist because the machine-readable fields do *not* match the display formats
 * the shared helpers expect — a timestamp carries seconds and a price carries no currency sign —
 * which is exactly what a first pass got wrong.
 */
class TempodromOverviewPageScraperTest {
    private val scraper = TempodromOverviewPageScraper()
    private val baseUrl = "https://www.tempodrom.de/programm-und-tickets/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/tempodrom/tempodrom-programme.html")!!
                .bufferedReader()
                .readText()
        events = scraper.scrape(Jsoup.parse(html, baseUrl))
    }

    private fun event(idSuffix: String): ScrapedEvent = events.first { it.sourceId == "tempodrom:$idSuffix" }

    @Test
    fun `extracts the whole programme from one JSON-LD block`() {
        // 145 objects, less the snooker and the esports fixture.
        events shouldHaveSize 143
        events.map { it.sourceId }.distinct() shouldHaveSize 143
    }

    @Test
    fun `types a night whose format line says comedy as COMEDY and keeps the performer`() {
        val polak = event("oliver_polak_2026-11-28_20")
        polak.subtitle shouldBe "COMEDY - Clubtour 2026"
        polak.eventType shouldBe "COMEDY"
        polak.artists.map { it.name } shouldContainExactly listOf("Oliver Polak")

        event("gazelle_2026-10-23_20").eventType shouldBe "COMEDY"
        event("dr-pop_2027-11-12_20").eventType shouldBe "COMEDY"
    }

    @Test
    fun `marks a CONCERT as the fallback a comedian headliner may retype, and a cued night not`() {
        event("baby_keem_2026-09-01_20").typeIsFallback shouldBe true
        event("oliver_polak_2026-11-28_20").typeIsFallback shouldBe false
    }

    @Test
    fun `parses every field of a dated concert`() {
        val babyKeem = event("baby_keem_2026-09-01_20")
        babyKeem.title shouldBe "Baby Keem"
        // The venue's `description` is the tour name, not a blurb.
        babyKeem.subtitle shouldBe "The Ca\$ino Tour"
        babyKeem.eventType shouldBe "CONCERT"
        babyKeem.eventDate shouldBe LocalDate.of(2026, 9, 1)
        // `startDate` is "2026-09-01T20:30:00" — seconds and all.
        babyKeem.startTime shouldBe LocalTime.of(20, 30)
        babyKeem.doorsTime shouldBe LocalTime.of(18, 30)
        // `endDate: "2026-09-01"` repeats the start date without a time, which says nothing.
        babyKeem.endDate.shouldBeNull()
        babyKeem.endTime.shouldBeNull()
        babyKeem.status shouldBe "SCHEDULED"
        babyKeem.soldOut shouldBe false
        babyKeem.sourceUrl shouldStartWith "https://www.tempodrom.de/event/"
        babyKeem.imageUrl.shouldNotBeNull() shouldStartWith "https://www.tempodrom.de/"
        babyKeem.artists shouldContainExactly listOf(ScrapedArtist("Baby Keem", "HEADLINER", titleDerived = true))
    }

    @Test
    fun `reads a price that carries no currency sign`() {
        // `offers.lowPrice` is the bare string "65.00"; the shared display-price parser needs a €.
        val babyKeem = event("baby_keem_2026-09-01_20")
        babyKeem.pricePresale shouldBe BigDecimal("65.00")
        babyKeem.priceNote shouldBe "65.00 – 70.75 EUR"
    }

    @Test
    fun `keeps the offer's range in the note so the low price is not read as the price`() {
        val ranged = events.filter { it.priceNote != null }
        ranged shouldHaveSize 66
        ranged.all { it.pricePresale != null } shouldBe true
    }

    @Test
    fun `parses times for every event that states one`() {
        // Five multi-day runs publish a date-only `startDate` and so have no clock time at all.
        events.count { it.startTime != null } shouldBe 140
        events.count { it.doorsTime != null } shouldBe 140
    }

    @Test
    fun `stores a multi-day run from its opening day to its closing day`() {
        val congress = event("berlin_salsacongress_2026_2026-08-27_2026-08-30_00")
        congress.eventDate shouldBe LocalDate.of(2026, 8, 27)
        // A date-only start carries no time; the date-only `endDate` is the run's last day (ADR-029).
        congress.startTime.shouldBeNull()
        congress.endDate shouldBe LocalDate.of(2026, 8, 30)
        congress.endTime.shouldBeNull()
        congress.subtitle shouldBe "Jungle Vibes Edition"
    }

    @Test
    fun `maps the schema-org status and availability vocabulary`() {
        events.count { it.status == "CANCELLED" } shouldBe 3
        events.count { it.soldOut } shouldBe 2
        events.none { it.soldOut && it.status == "CANCELLED" } shouldBe true
    }

    @Test
    fun `resolves a date for every event`() {
        events.none { it.eventDate == LocalDate.MIN } shouldBe true
        events.all { it.imageUrl != null } shouldBe true
    }

    @Test
    fun `derives the headliner from the title, not the placeholder performer`() {
        // `performer.name` is a copy of the event name on all 145 events, so it names no act. Three
        // of the rest are score concerts (`The Witcher in Concert`), which bill no act (#1829), and
        // three are the Roncalli gala with an orchestra, a show (#2833). Holiday on Ice and
        // "Eiskönigin 1 & 2" are ice shows, which bill no act in the title. `Die Nacht der Musicals`
        // is a gala named for its night (#3160), and `Berlin Salsacongress 2026` a congress named for its year (#3212).
        // Six productions bill no act from the title (#3162).
        events.count { it.artists.isNotEmpty() } shouldBe 125
        events.flatMap { it.artists }.all { it.role == "HEADLINER" } shouldBe true
    }

    @Test
    fun `types the Roncalli gala with an orchestra as SHOW and bills no artist`() {
        // A circus gala the Deutsches Symphonie-Orchester accompanies, not an orchestral concert (#2833).
        val gala = event("roncalli_und_deutsches_symphonie-orchester_berlin_2026-12-31_19")
        gala.eventType shouldBe "SHOW"
        gala.artists.shouldBeEmpty()
    }

    @Test
    fun `types an ice show as SHOW and mints no artist from its title`() {
        val holidayOnIce = event("holiday_on_ice_2027-02-26_2027-03-14_00")
        holidayOnIce.eventType shouldBe "SHOW"
        holidayOnIce.typeIsFallback shouldBe false
        holidayOnIce.artists.shouldBeEmpty()
    }

    @Test
    fun `types an ice show named only in the format line as SHOW`() {
        // "Eiskönigin 1 & 2" carries no ice cue in its title; its description reads "Musik-Show auf Eis".
        val eiskoenigin = event("eiskoenigin_1_und_2_2027-01-22_18")
        eiskoenigin.subtitle shouldBe "Musik-Show auf Eis"
        eiskoenigin.eventType shouldBe "SHOW"
        eiskoenigin.typeIsFallback shouldBe false
        eiskoenigin.artists.shouldBeEmpty()
    }

    @Test
    fun `types a production SHOW and bills no act from its title`() {
        // ELVIS / "Das Musical", "Die Originalproduktion …", "… Musicalgala …", "Das Tribute-Konzert …" (#3162).
        listOf(
            "elvis_2027-04-04_19",
            "das_phantom_der_oper_2027-01-21_20",
            "die_nacht_der_musicals_2027-03-17_20",
            "pop_divas-der_soundtrack_deines_lebens_2027-03-18_19",
            "drei_haselnuesse_fuer_aschenbroedel_2026-12-08_19"
        ).forEach { id ->
            val production = event(id)
            production.eventType shouldBe "SHOW"
            production.typeIsFallback shouldBe false
            production.artists.shouldBeEmpty()
        }
    }

    @Test
    fun `keeps an act whose tour line calls the music a musical`() {
        val klaws = event("alexander_klaws_2026-11-25_19")
        klaws.subtitle shouldBe "A VERY SPECIAL MUSICAL CHRISTMAS TOUR 2026"
        klaws.eventType shouldBe "CONCERT"
        klaws.artists.map { it.name } shouldContainExactly listOf("ALEXANDER KLAWS")
    }

    @Test
    fun `types a film-music night CONCERT and bills no act`() {
        val klassikRadio = event("klassik_radio_2026-11-08_19")
        klassikRadio.subtitle shouldBe "Live in Concert - Die Nacht der Filmmusik"
        klassikRadio.eventType shouldBe "CONCERT"
        klassikRadio.typeIsFallback shouldBe false
        klassikRadio.artists.shouldBeEmpty()

        val filmNight = scrapeOne("Die Nacht der Filmmusik 2027", "Live")
        filmNight.eventType shouldBe "CONCERT"
        filmNight.artists.shouldBeEmpty()
    }

    @Test
    fun `bills the orchestra an original film names and no act from the title`() {
        val film = scrapeOne("Drei Haselnüsse für Aschenbrödel", "Der Originalfilm mit dem Deutschen Filmorchester Babelsberg")

        film.eventType shouldBe "SHOW"
        film.artists shouldContainExactly listOf(ScrapedArtist("Deutsches Filmorchester Babelsberg", "HEADLINER"))
    }

    @Test
    fun `types the Loriot programme SHOW and bills no act`() {
        val loriot = event("loriot_2027-02-14_14")
        loriot.eventType shouldBe "SHOW"
        loriot.artists.shouldBeEmpty()
    }

    @Test
    fun `types a dance congress OTHER and bills no act`() {
        event("berlin_salsacongress_2026_2026-08-27_2026-08-30_00").eventType shouldBe "OTHER"

        val congress = scrapeOne("Berlin Salsacongress 2027", "Jungle Reloaded Edition")
        congress.eventType shouldBe "OTHER"
        congress.artists.shouldBeEmpty()
    }

    @Test
    fun `types a guest-and-host talk READING and bills no act`() {
        val talk = scrapeOne("MISSVERSTEHEN SIE MICH RICHTIG - Robert Habeck zu Gast bei Maja Göpel", "Der Realist mit Restzweifel")

        talk.eventType shouldBe "READING"
        talk.artists.shouldBeEmpty()
    }

    @Test
    fun `bills the cast a mit line names instead of the title`() {
        val finale = scrapeOne("Das große Fun Facts Jahresfinale", "mit Marc-Uwe Kling, Sarah Bosetti uvm.")

        finale.artists.map { it.name } shouldContainExactly listOf("Marc-Uwe Kling", "Sarah Bosetti")
    }

    @Test
    fun `bills the act in front of sings and of a bracketed show`() {
        event("thomas_anders_sings_modern_talking_2027-05-16_20").artists.map { it.name } shouldContainExactly listOf("Thomas Anders")
        scrapeOne("SHADEN (أنا إمرأة تعبانة) Show", "// Berlin | Kleine Arena").artists.map { it.name } shouldContainExactly listOf("SHADEN")
    }

    @Test
    fun `does not store the listing URL as a ticket link`() {
        // Many offers repeat the event's own URL; only a genuinely external shop link is a ticket.
        events.none { it.ticketUrl != null && it.ticketUrl == it.sourceUrl } shouldBe true
    }

    // The JSON-LD is script content, which Jsoup hands back undecoded, and the CMS escapes what it
    // writes there. Left raw, "&amp;" reached the title, the derived headliner and both slugs
    // (`scala-amp-kolacny-brothers`), and hid the `&` from the co-bill splitter.
    @Test
    fun `decodes the HTML entities the venue leaves in its JSON-LD`() {
        events.map { it.title }.none { it.contains("&amp;") } shouldBe true
        events.flatMap { it.artists }.map { it.name }.none { it.contains("&amp;") } shouldBe true

        event("beisenherz_und_polak-friendly_fire_2026-09-13_19").title shouldBe "Beisenherz & Polak - Friendly Fire"
    }

    @Test
    fun `keeps a decoded ampersand act whole instead of splitting it into two headliners`() {
        // Decoding exposes the `&` to the co-bill splitter; this choir is one act, not two.
        val scala = events.first { it.title == "Scala & Kolacny Brothers" }

        scala.artists shouldContainExactly listOf(ScrapedArtist("Scala & Kolacny Brothers", "HEADLINER", titleDerived = true))
    }

    @Test
    fun `drops the snooker and the esports tournament`() {
        // "Snooker" / "German Masters 2027" (#2470) and "GeoGuessr" / "World Championship - 4" (#2478).
        events.none { it.sourceId.startsWith("tempodrom:snooker") } shouldBe true
        events.none { it.title.contains("Masters", ignoreCase = true) } shouldBe true
        events.none { it.sourceId.startsWith("tempodrom:geoguessr") } shouldBe true
    }

    @Test
    fun `drops an esports format line and keeps a world championship that names no game`() {
        val html =
            """
            <html><body><script type="application/ld+json">[
              {"@type": "Event", "name": "Berlin Major", "description": "E-Sport Finals 2027",
               "startDate": "2027-04-04T19:00:00+02:00", "url": "https://www.tempodrom.de/event/bm_2027-04-04_19/"},
              {"@type": "Event", "name": "Showdance", "description": "World Championship 2027",
               "startDate": "2027-04-05T20:00:00+02:00", "url": "https://www.tempodrom.de/event/sd_2027-04-05_20/"}
            ]</script></body></html>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, baseUrl)).map { it.title } shouldContainExactly listOf("Showdance")
    }

    @Test
    fun `drops a row whose format line names the sport and keeps a near miss`() {
        val html =
            """
            <html><body><script type="application/ld+json">[
              {"@type": "Event", "name": "Premier League", "description": "Darts 2027",
               "startDate": "2027-03-04T19:00:00+01:00", "url": "https://www.tempodrom.de/event/pl_2027-03-04_19/"},
              {"@type": "Event", "name": "Masters of Rock", "description": "Boxenstopp Tour",
               "startDate": "2027-03-05T20:00:00+01:00", "url": "https://www.tempodrom.de/event/mor_2027-03-05_20/"}
            ]</script></body></html>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, baseUrl)).map { it.title } shouldContainExactly listOf("Masters of Rock")
    }

    private fun scrapeOne(
        name: String,
        description: String
    ): ScrapedEvent {
        val html =
            """
            <html><body><script type="application/ld+json">[
              {"@type": "Event", "name": "$name", "description": "$description",
               "startDate": "2027-04-04T19:00:00+02:00", "url": "https://www.tempodrom.de/event/x_2027-04-04_19/"}
            ]</script></body></html>
            """.trimIndent()
        return scraper.scrape(Jsoup.parse(html, baseUrl)).single()
    }

    @Test
    fun `moves the room the description ends in from the subtitle to the room`() {
        val polak = event("oliver_polak_2026-11-28_20")
        polak.room shouldBe "Kleine Arena"

        val azeem = scrapeOne("Azeem Banatwalla", "Germany Tour | Kleine Arena")
        azeem.subtitle shouldBe "Germany Tour"
        azeem.room shouldBe "Kleine Arena"

        scrapeOne("Abdullah Sbeih", "Proposal | Stand-Up Comedy Show | Kleine Arena").subtitle shouldBe "Proposal | Stand-Up Comedy Show"
        scrapeOne("Big Act", "World Tour | Große Arena").room shouldBe "Große Arena"
    }

    @Test
    fun `stores a description that is only the room as the room and no subtitle`() {
        val robby = scrapeOne("Robby Hoffman", "Kleine Arena")

        robby.subtitle.shouldBeNull()
        robby.room shouldBe "Kleine Arena"
    }

    @Test
    fun `keeps a description without a room whole and leaves the room empty`() {
        val event = scrapeOne("Some Act", "Tour 2026 | Ersatztermin vom 07.05.2026")

        event.subtitle shouldBe "Tour 2026 | Ersatztermin vom 07.05.2026"
        event.room.shouldBeNull()
        scrapeOne("Arena Rock", "Arena Tour").room.shouldBeNull()
    }

    @Test
    fun `returns no events for a page without JSON-LD`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", baseUrl)).shouldBeEmpty()
    }

    @Test
    fun `returns no events for an unparseable JSON-LD block`() {
        val html = """<html><body><script type="application/ld+json">{ not json </script></body></html>"""
        scraper.scrape(Jsoup.parse(html, baseUrl)).shouldBeEmpty()
    }
}
