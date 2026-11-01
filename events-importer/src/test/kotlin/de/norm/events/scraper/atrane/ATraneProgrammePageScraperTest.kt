package de.norm.events.scraper.atrane

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventEntity
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [ATraneProgrammePageScraper], against a snapshot taken on 2026-10-01. */
class ATraneProgrammePageScraperTest {
    private val sourceUrl = "https://a-trane.de/programm/"
    private val events = ATraneProgrammePageScraper().scrape(Jsoup.parse(fixture(), sourceUrl))

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/atrane/atrane-programm.html")!!
            .bufferedReader()
            .readText()

    private fun on(date: LocalDate): ScrapedEvent = events.single { it.eventDate == date }

    /** The row the importer stores, where the description is cut in two (#2986). */
    private fun stored(slug: String): EventEntity =
        events.single { it.sourceId.contains(slug) }.toEventEntity(venueId = 1L, venueSlug = "a-trane", eventSourceId = 1L)

    @Test
    fun `parses every concert of the programme once`() {
        // 61 nights: five closed days and one show in another house are left out.
        events shouldHaveSize 55
        events.map { it.sourceId }.distinct() shouldHaveSize 55
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 1)
        events.last().eventDate shouldBe LocalDate.of(2026, 12, 9)
        events.map { it.eventType }.distinct() shouldBe listOf(EventType.CONCERT.name)
    }

    @Test
    fun `parses a fully populated night`() {
        val night = on(LocalDate.of(2026, 10, 13))

        night.title shouldBe "VIGNOLO-MUELLER"
        night.subtitle shouldBe "Jazzquintett · «Jazz ohne Grenzen» · FEAT: Florian Menzel-Markus Schieferdecker-Étienne Deconfin"
        night.startTime shouldBe LocalTime.of(20, 30)
        night.genre shouldBe "MODERN TRADITION JAZZ"
        night.pricePresale shouldBe BigDecimal("25.00")
        night.free shouldBe false
        night.sourceUrl shouldBe
            "https://a-trane.de/Events-Directory/a-trane-praesentiertvignolo-muellerjazzquintettjazz-ohne-grenzenfeat-florian-menzel-markus-schieferdecker-etienne-deconfin/"
        night.sourceId shouldBe
            "a_trane:a-trane-praesentiertvignolo-muellerjazzquintettjazz-ohne-grenzenfeat-florian-menzel-markus-schieferdecker-etienne-deconfin"
        night.imageUrl shouldBe "https://a-trane.de/wp-content/uploads/2026/09/VIGNOLO-MUELLER-.jpg"
        night.description!! shouldStartWith "VIGNOLO-MUELLER\nJazzquintett"
        night.artists.map { it.name } shouldBe listOf("VIGNOLO-MUELLER")
    }

    @Test
    fun `reads an unpadded start date as wall-clock time`() {
        // "2026-11-1T20:00+2:00": the offset stays +2:00 in winter.
        val night = on(LocalDate.of(2026, 11, 1))
        night.startTime shouldBe LocalTime.of(20, 0)
        night.title shouldBe "Twin Talk «Delights»"
    }

    @Test
    fun `takes a festival night's act from its tonight-with line`() {
        val night = on(LocalDate.of(2026, 10, 29))
        night.title shouldBe "Elin Forkelid «Plays For Trane»"
        night.subtitle shouldBe "Feat: David Stackenäs-Mattias Ståhl-Vilhelm Bromander-Jon Fält"
        night.pricePresale.shouldBeNull()
    }

    @Test
    fun `marks the weekly free session free and drops its empty lineup label`() {
        val night = on(LocalDate.of(2026, 10, 5))
        night.title shouldBe "ANDREAS SCHMIDT & FRIENDS"
        night.subtitle.shouldBeNull()
        night.free shouldBe true
        night.pricePresale.shouldBeNull()
        night.genre shouldBe "modern jazz"
    }

    /** One weekly-session card whose JSON-LD name is the credit and [lines], `<br>`-separated. */
    private fun session(vararg lines: String): ScrapedEvent = card(listOf("A-TRANE PRÄSENTIERT:") + lines)

    /** One card whose JSON-LD name is [lines], `<br>`-separated, starting at [start] with [subtitle] in its markup. */
    private fun card(
        lines: List<String>,
        start: String = "21:00",
        subtitle: String = ""
    ): ScrapedEvent {
        val name = lines.joinToString("&lt;br&gt;") { it.replace("&", "&amp;") }
        val html =
            """
            <div class="eventon_list_event" data-event_id="1">
              <script type="application/ld+json">
                {"@context": "http://schema.org", "@type": "Event", "name": "$name",
                 "url": "https://a-trane.de/Events-Directory/andreas-schmidt/", "startDate": "2026-10-5T$start+2:00"}
              </script>
              <span class="evcal_event_subtitle">$subtitle</span>
            </div>
            """.trimIndent()
        return ATraneProgrammePageScraper().scrape(Jsoup.parse(html, sourceUrl)).single()
    }

    @Test
    fun `reads the admission time a winter-Sunday subtitle or a day-of seats notice prints`() {
        // #3870: "EINLASS 17.00 UHR KONZERTBEGINN: 18.00 UHR" and "EINLASS: 19.00 UR KONZERTBEGINN 20.00 UHR".
        on(LocalDate.of(2026, 10, 25)).doorsTime shouldBe LocalTime.of(17, 0)
        on(LocalDate.of(2026, 10, 11)).doorsTime shouldBe LocalTime.of(17, 0)
        on(LocalDate.of(2026, 10, 29)).doorsTime shouldBe LocalTime.of(19, 0)
        on(LocalDate.of(2026, 10, 13)).doorsTime.shouldBeNull()
    }

    @Test
    fun `reads a one-night special admission time with spaces before its colons`() {
        val night =
            card(
                listOf("A-TRANE PRÄSENTIERT DAY1:", "ERNST BIER NEW QUARTET"),
                start = "22:00",
                subtitle = "WICHTIG! NUR AM 25.12. 1. WEIHNACHTSTAG SONDERZEIT: EINLASS : 21.00 UHR BEGINN : 22.00 UHR / IMPORTANT!"
            )
        night.doorsTime shouldBe LocalTime.of(21, 0)
        night.startTime shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `keeps no admission time a moved night's notice still prints`() {
        // The 2026-11-08 night starts at 20:30 under the winter notice for an 18:00 start.
        on(LocalDate.of(2026, 11, 8)).doorsTime.shouldBeNull()
    }

    @Test
    fun `keeps no admission time that is not before the start`() {
        card(listOf("A-TRANE PRÄSENTIERT:", "TRIO"), start = "20:00", subtitle = "EINLASS 20.00 UHR").doorsTime.shouldBeNull()
        card(listOf("A-TRANE PRÄSENTIERT:", "TRIO"), start = "20:00", subtitle = "EINLASS 21.00 UHR").doorsTime.shouldBeNull()
        card(listOf("A-TRANE PRÄSENTIERT:", "TRIO"), start = "20:00", subtitle = "EINLASS 19.30 UHR").doorsTime shouldBe LocalTime.of(19, 30)
    }

    @Test
    fun `bills the host of the weekly session without its unnamed friends`() {
        // Event 12155: an empty "HEUTE MIT:" leaves the host as the only act.
        val night = session("ANDREAS SCHMIDT & FRIENDS", "HEUTE MIT:", "EINTRITT FREI & ENTRANCE FREE")

        night.title shouldBe "ANDREAS SCHMIDT & FRIENDS"
        night.artists.map { it.name to it.role } shouldBe listOf("ANDREAS SCHMIDT" to ArtistRole.HEADLINER)
    }

    @Test
    fun `keeps the weekly session's act and bills the guests it names`() {
        // #2706: a filled "HEUTE MIT:" under the act names guests; it is not a festival's act line.
        val night = session("ANDREAS SCHMIDT & FRIENDS", "HEUTE MIT: ROLAND SCHNEIDER- CHRISTIAN KÖGEL")

        night.title shouldBe "ANDREAS SCHMIDT & FRIENDS"
        night.subtitle shouldBe "Heute mit: Roland Schneider, Christian Kögel"
        night.artists.map { it.name to it.role } shouldBe
            listOf("ANDREAS SCHMIDT" to ArtistRole.HEADLINER, "Roland Schneider" to ArtistRole.SUPPORT, "Christian Kögel" to ArtistRole.SUPPORT)
    }

    @Test
    fun `splits guests only where a dash is followed by a space, and bills no placeholder`() {
        // A hyphen inside a double-barrelled name is not a separator; an en dash between names is.
        val night = session("ANDREAS SCHMIDT & FRIENDS", "HEUTE MIT: HANS-PETER MEIER- ANNA LENA – N.N.")

        night.artists.filter { it.role == ArtistRole.SUPPORT }.map { it.name } shouldBe listOf("Hans-Peter Meier", "Anna Lena")
        night.subtitle shouldBe "Heute mit: Hans-Peter Meier, Anna Lena"
    }

    @Test
    fun `keeps a line below the guests in the subtitle, after them`() {
        val night = session("ANDREAS SCHMIDT & FRIENDS", "HEUTE MIT: ROLAND SCHNEIDER", "JAM SESSION AB 22 UHR")

        night.title shouldBe "ANDREAS SCHMIDT & FRIENDS"
        night.subtitle shouldBe "Heute mit: Roland Schneider · JAM SESSION AB 22 UHR"
        night.artists.map { it.name } shouldBe listOf("ANDREAS SCHMIDT", "Roland Schneider")
    }

    @Test
    fun `bills the guest a special-guest line names, and no sideman`() {
        // #3105: "WITH: Thomas Stieger-Moritz Müller" stays unsplit in the subtitle; "FEATURING SPECIAL GUEST:" names one guest.
        val night = on(LocalDate.of(2026, 10, 11))

        night.artists.map { it.name to it.role } shouldBe listOf("Christian Frentzen" to ArtistRole.HEADLINER, "Nir Felder" to ArtistRole.SUPPORT)
        night.subtitle shouldBe
            "Roundrobyn Records Album Release Concert · WITH: Thomas Stieger-Moritz Müller · FEATURING SPECIAL GUEST: Nir Felder"
    }

    @Test
    fun `bills a special guest under a sidemen line unsplit`() {
        val night = session("BILL PETRY", "«CLOSE YOUR EYES»", "FEAT: Christian von der Goltz-Olaf Casimir-Tobias Backhaus", "SPECIAL GUEST: Irma Neumüller")

        night.title shouldBe "BILL PETRY"
        night.artists.map { it.name to it.role } shouldBe listOf("BILL PETRY" to ArtistRole.HEADLINER, "Irma Neumüller" to ArtistRole.SUPPORT)
        night.subtitle shouldBe "«CLOSE YOUR EYES» · FEAT: Christian von der Goltz-Olaf Casimir-Tobias Backhaus · SPECIAL GUEST: Irma Neumüller"
    }

    @Test
    fun `bills the act below a title that only leads in to it, and splits plural special guests`() {
        val night =
            card(
                listOf(
                    "A-TRANE PRÄSENTIERT CHRISTMAS TAG4:",
                    "WEIHNACHTLICH BESINNLICHES MIT:",
                    "THOMAS QUASTHOFF",
                    "FEAT: ROLF ZIELKE / PETER WENIGER / OLAF CASIMIR / THOMAS ALKIER",
                    "SPECIAL GUESTS: JUTTA SPEIDEL & SIMON OSLENDER"
                )
            )

        night.title shouldBe "WEIHNACHTLICH BESINNLICHES MIT:"
        night.subtitle shouldBe
            "THOMAS QUASTHOFF · FEAT: ROLF ZIELKE / PETER WENIGER / OLAF CASIMIR / THOMAS ALKIER · SPECIAL GUESTS: JUTTA SPEIDEL & SIMON OSLENDER"
        night.artists.map { it.name to it.role } shouldBe
            listOf("THOMAS QUASTHOFF" to ArtistRole.HEADLINER, "JUTTA SPEIDEL" to ArtistRole.SUPPORT, "SIMON OSLENDER" to ArtistRole.SUPPORT)
    }

    @Test
    fun `bills the act a meets line quotes, not its members`() {
        val night =
            card(
                listOf(
                    "A-TRANE PRÄSENTIERT CHRISTMAS TAG3:",
                    "WEIHNACHTLICH BESINNLICHES MIT:",
                    "THOMAS QUASTHOFF",
                    "FEAT: ROLF ZIELKE / PETER WENIGER / OLAF CASIMIR / THOMAS ALKIER",
                    // Escaped for the JSON-LD the helper writes.
                    "MEETS \\\"THE SHAWN & WOLF» Shawn Grocott & Wolfgang Meyer"
                )
            )

        night.artists.map { it.name to it.role } shouldBe listOf("THOMAS QUASTHOFF" to ArtistRole.HEADLINER, "THE SHAWN & WOLF" to ArtistRole.SUPPORT)
        night.subtitle!! shouldEndWith "MEETS \"THE SHAWN & WOLF» Shawn Grocott & Wolfgang Meyer"
    }

    @Test
    fun `bills the resident without the residency label`() {
        val night =
            card(
                listOf(
                    "A-TRANE PRÄSENTIERT THE NEW YEAR TRADITION CONCERT DAY2:",
                    "ARTIST IN RESIDENCE Jeremy Sassoon",
                    "TODAY TRIO FEAT: BJÖRN WERRA-MICHAEL KERSTING",
                    "SPECIAL GUEST: FLORIAN MENZEL"
                )
            )

        night.title shouldBe "ARTIST IN RESIDENCE Jeremy Sassoon"
        night.artists.map { it.name to it.role } shouldBe listOf("Jeremy Sassoon" to ArtistRole.HEADLINER, "FLORIAN MENZEL" to ArtistRole.SUPPORT)
    }

    @Test
    fun `splits a co-bill whose slash is spaced on one side only`() {
        val night = session("Esther Kaiser/ Alexander Wienand", "«BREATHING» Vinyl Release Concert", "Feat: Tilmann Dehnhard-Athina Kontou")

        night.title shouldBe "Esther Kaiser/ Alexander Wienand"
        night.artists.map { it.name to it.role } shouldBe listOf("Esther Kaiser" to ArtistRole.HEADLINER, "Alexander Wienand" to ArtistRole.HEADLINER)
    }

    @Test
    fun `bills a duo named for its members' first names as one act`() {
        // Events 12321 and 5174: the club and the duos bill themselves as one name.
        session("SHUTEEN & NILS", "«WITH FRIENDS»", "FEAT: RONI EYTAN-LUCA ZAMBITO").artists.map { it.name } shouldBe listOf("SHUTEEN & NILS")
        session("ZOHAR & ADAM", "«OSMOSIS»").artists.map { it.name } shouldBe listOf("ZOHAR & ADAM")
    }

    @Test
    fun `bills a special guest the title already names once`() {
        val nights = events.filter { it.title.startsWith("Tony Hurdle") }

        nights shouldHaveSize 2
        nights.forEach { night -> night.artists.count { it.name.contains("TC THE 3RD", ignoreCase = true) } shouldBe 1 }
    }

    @Test
    fun `drops a last-seats banner above the credit and bills the act below it`() {
        // #3165: the 2026-10-10 night was stored with the banner as its title and its act.
        val night = on(LocalDate.of(2026, 10, 10))

        night.title shouldBe "DAVID HAYNES AND FRIENDS"
        night.subtitle!! shouldStartWith "«SOULFUL FALL» · WITH SPECIAL GUESTS"
        night.artists.map { it.name to it.role } shouldBe listOf("DAVID HAYNES" to ArtistRole.HEADLINER)
    }

    @Test
    fun `drops a sold-out banner in either language and a singular last-seat line`() {
        listOf("AUSVERKAUFT", "SOLD OUT", "Ausverkauft / Sold out", "LETZTE PLÄTZE", "Letzte Plaetze an der Bar").forEach { banner ->
            val night = card(listOf(banner, "A-TRANE PRÄSENTIERT:", "BILL PETRY", "«CLOSE YOUR EYES»"))

            night.title shouldBe "BILL PETRY"
            night.subtitle shouldBe "«CLOSE YOUR EYES»"
            night.artists.map { it.name } shouldBe listOf("BILL PETRY")
        }
    }

    @Test
    fun `names the act without its quoted album title`() {
        on(LocalDate.of(2026, 12, 5)).artists.map { it.name } shouldBe listOf("MIKE RUSSELL")
        on(LocalDate.of(2026, 10, 7)).artists.map { it.name } shouldBe listOf("ENEMY")
        on(LocalDate.of(2026, 10, 11)).title shouldBe "Christian Frentzen «PULSES»"
        on(LocalDate.of(2026, 10, 11)).artists.first().name shouldBe "Christian Frentzen"
    }

    @Test
    fun `joins every style tag into the genre`() {
        on(LocalDate.of(2026, 11, 13)).genre shouldBe "Colorful Latin American Music Weekend, jazz tango world music"
    }

    @Test
    fun `takes the full price, not the student rate`() {
        on(LocalDate.of(2026, 10, 2)).pricePresale shouldBe BigDecimal("35.00")
        on(LocalDate.of(2026, 10, 28)).pricePresale shouldBe BigDecimal("20.00")
    }

    @Test
    fun `leaves out closed days and a show in another house`() {
        val titles = events.map { it.title.lowercase() }
        titles.filter { "geschlossen" in it }.shouldBeEmpty()
        events.map { it.eventDate } shouldNotContain LocalDate.of(2026, 11, 27)
    }

    @Test
    fun `returns nothing for a page without cards`() {
        ATraneProgrammePageScraper().scrape(Jsoup.parse("<html><body></body></html>", sourceUrl)).shouldBeEmpty()
    }

    @Test
    fun `skips a card without a readable start`() {
        val html =
            """
            <div class="eventon_list_event" data-event_id="1">
              <script type="application/ld+json">
                {"@context": "http://schema.org", "@type": "Event", "name": "A-TRANE PRÄSENTIERT:&lt;br&gt;Trio",
                 "url": "https://a-trane.de/Events-Directory/trio/", "startDate": "soon"}
              </script>
            </div>
            """.trimIndent()
        ATraneProgrammePageScraper().scrape(Jsoup.parse(html, sourceUrl)).shouldBeEmpty()
    }

    // The programme writes "Deutsch" and "English" headings into one description (#330).
    @Test
    fun `cuts a description in both languages at its headings, with the title lines and the line-up in both`() {
        val kera = stored("mfa-kera-and-black-heritage")

        val german = kera.description.shouldNotBeNull()
        val english = kera.descriptionAlt.shouldNotBeNull()
        german shouldStartWith "MFA KERA AND BLACK HERITAGE\nFEAT: Mike RUSSELL\n«THE AFROSOUL JOURNEY»\nSeid mit dabei"
        english shouldStartWith "MFA KERA AND BLACK HERITAGE\nFEAT: Mike RUSSELL\n«THE AFROSOUL JOURNEY»\nJoin the group"
        german shouldEndWith "Charles Sammons – Bass\nFoto Kera©Bernd Leideritz"
        english shouldEndWith "Charles Sammons – Bass\nFoto Kera©Bernd Leideritz"
        german shouldNotContain "Join the group"
        german shouldNotContain "\nEnglish\n"
    }

    @Test
    fun `keeps the venue's order, so a text that opens in English stays the description`() {
        val bresler = stored("amir-bresler")

        bresler.description.shouldNotBeNull() shouldContain "Led by Amir Bresler"
        bresler.descriptionAlt.shouldNotBeNull() shouldContain "Unter der Leitung von Amir Bresler"
    }

    // English press quotes inside the German half leave it no single language, so the text stays whole.
    @Test
    fun `stores a half that mixes in the other language as published`() {
        val ruppnig = stored("mathias-ruppnigfoam")

        ruppnig.descriptionAlt.shouldBeNull()
        ruppnig.description.shouldNotBeNull() shouldContain "ENGLISH:"
    }

    @Test
    fun `drops the club's own name from a homecoming title`() {
        val homecoming = fixture().replace("VIGNOLO-MUELLER", "JC DOOK BACK AT A-TRANE")
        val night = ATraneProgrammePageScraper().scrape(Jsoup.parse(homecoming, sourceUrl)).single { it.eventDate == LocalDate.of(2026, 10, 13) }
        night.artists
            .first()
            .name
            .lowercase() shouldBe "jc dook"
    }
}
