package de.norm.events.scraper.slaughterhouse

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class SlaughterhouseOverviewPageScraperTest {
    private val baseUrl = "https://slaughterhouse-berlin.de/konzerte/"
    private val events = SlaughterhouseOverviewPageScraper().scrape(fixtureDocument("slaughterhouse/slaughterhouse-konzerte.html", baseUrl), baseUrl)

    private val november =
        SlaughterhouseOverviewPageScraper().scrape(fixtureDocument("slaughterhouse/slaughterhouse-konzerte-2026-11.html", baseUrl), baseUrl)

    private fun on(day: Int) = events.first { it.eventDate == LocalDate.of(2026, 10, day) }

    private fun on(
        month: Int,
        day: Int
    ) = events.single { it.eventDate == LocalDate.of(2026, month, day) }

    private fun billing(
        month: Int,
        day: Int
    ) = on(month, day).artists.map { it.name to it.role }

    @Test
    fun `reads one event per entry between the separators`() {
        events.map { it.eventDate.toString() } shouldBe
            listOf(
                "2026-10-10",
                "2026-10-16",
                "2026-10-17",
                "2026-10-30",
                "2026-10-31",
                "2026-11-06",
                "2026-11-07",
                "2026-11-14",
                "2026-12-05",
                "2026-12-12",
                "2026-12-19"
            )
        val concert = on(16)
        concert.title shouldBe "Tyske Ludder + To Avoid"
        concert.eventType shouldBe EventType.CONCERT.name
        concert.startTime shouldBe LocalTime.of(22, 0)
        concert.sourceId shouldBe "slaughterhouse:2026-10-16"
        concert.sourceUrl shouldBe baseUrl
        concert.imageUrl shouldBe "https://slaughterhouse-berlin.de/wp-content/uploads/2026/09/image-3.png"
        concert.artists.map { it.name } shouldBe listOf("Tyske Ludder", "To Avoid")
        concert.description!! shouldNotContain "Tickets:"
        concert.description shouldContain "\n\n" // a gap between the paragraphs (events 13953, 13955)
    }

    @Test
    fun `unwraps a ticket link pasted through Facebook's redirect, and stores it without the click ID`() {
        on(16).ticketUrl!! shouldStartWith "https://www.tixforgigs.com/Event/67150?fbclid="
        on(16).toEventEntity(venueId = 1L, venueSlug = "slaughterhouse", eventSourceId = 1L).ticketUrl shouldBe "https://www.tixforgigs.com/Event/67150"
        on(17).ticketUrl shouldBe "https://www.eventim-light.com/de/a/5c8824131eb12100019a7054/"
    }

    @Test
    fun `reads a time line without a colon`() {
        val satisfactory = events.single { it.eventDate == LocalDate.of(2026, 12, 19) }
        satisfactory.startTime shouldBe LocalTime.of(22, 0)
        satisfactory.doorsTime.shouldBeNull()
        satisfactory.description!! shouldNotContain "Beginn"
        val madness = events.single { it.eventDate == LocalDate.of(2026, 11, 6) }
        madness.doorsTime shouldBe LocalTime.of(21, 0)
        madness.startTime.shouldBeNull()
        madness.description!! shouldNotContain "doors open"
    }

    @Test
    fun `reads the start from a doors line's caption that names the time before Beginn`() {
        val factory = on(31)
        factory.doorsTime shouldBe LocalTime.of(22, 0)
        factory.startTime shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `keeps a description line that opens with Beginn but names no time`() {
        val html =
            """
            <div class="entry-content">
            <p class="wp-block-paragraph">13.11.2026<br><strong>Konzert: Tyske Ludder</strong></p>
            <p class="wp-block-paragraph">Doors 20.30<br>Beginn der Tour in Berlin.<br>Tickets: 5 vor 12! Bis 12 Uhr 5 Euro</p>
            </div>
            """.trimIndent()
        val night = SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).single()
        night.doorsTime shouldBe LocalTime.of(20, 30)
        night.startTime.shouldBeNull()
        night.description shouldBe "Beginn der Tour in Berlin."
    }

    @Test
    fun `reads a party's style line and DJs, and its doors`() {
        val party = on(10)
        party.eventType shouldBe EventType.PARTY.name
        party.genre shouldBe "minimal, synth, wave"
        party.artists.map { it.name } shouldBe listOf("marko könig", "andre s.", "hazi")
        val factory = on(31)
        factory.artists.shouldBeEmpty()
        factory.imageUrl shouldBe "https://slaughterhouse-berlin.de/wp-content/uploads/2024/02/image-1.png"
        factory.description!! shouldNotContain "– – –"
    }

    @Test
    fun `bills the band before a title's colon and reads the door price`() {
        val night = on(30)
        night.eventType shouldBe EventType.CONCERT.name
        night.artists.map { it.name } shouldBe listOf("Capper")
        night.priceBoxOffice shouldBe BigDecimal("20.00")
        on(17).artists.map { it.name } shouldBe listOf("Blutgott")
    }

    @Test
    fun `bills the co-bill after a night's name and keeps the title`() {
        val html =
            """
            <div class="entry-content">
            <p class="wp-block-paragraph">13.11.2026<br><strong>Konzert+Party: Stoner&#8217;s Fall &#8211; Sheev + Ghostones + Caosonora</strong></p>
            <p class="wp-block-paragraph">Beginn: 21:00</p>
            </div>
            """.trimIndent()
        val night = SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).single()
        night.title shouldBe "Stoner’s Fall – Sheev + Ghostones + Caosonora"
        night.artists.map { it.name to it.role } shouldBe
            listOf("Sheev" to ArtistRole.HEADLINER, "Ghostones" to ArtistRole.HEADLINER, "Caosonora" to ArtistRole.HEADLINER)
    }

    @Test
    fun `bills event 13955's co-bill after the series colon, without each act's genre and town`() {
        val html =
            """
            <div class="entry-content">
            <p class="wp-block-paragraph">04.12.2026<br><strong>Konzert: Santa&#8217;s Riot VI: Deshurance – Hardcore (Berlin) + The IronY &#8211; Mixed Metal Arts (Berlin) + Ravenpath &#8211; Melodic Death Metal (Zwickau) </strong></p>
            <p class="wp-block-paragraph">Beginn: 19:00</p>
            </div>
            """.trimIndent()
        val night = SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).single()
        night.artists.map { it.name to it.role } shouldBe
            listOf("Deshurance", "The IronY", "Ravenpath").map { it to ArtistRole.HEADLINER }
    }

    @Test
    fun `bills event 13954's co-bill after a numbered series name and its colon`() {
        val html =
            """
            <div class="entry-content">
            <p class="wp-block-paragraph">27.11.2026<br><strong>Konzert: 34. Lange Nacht der Indieschönheiten: Planet Pankow + First&#8217;n&#8217;Stripes + GFR</strong></p>
            <p class="wp-block-paragraph">Beginn: 20:00</p>
            </div>
            """.trimIndent()
        val night = SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).single()
        night.artists.map { it.name } shouldBe listOf("Planet Pankow", "First’n’Stripes", "GFR")
    }

    @Test
    fun `bills event 13956's band without its record release concert tail`() {
        val html =
            """
            <div class="entry-content">
            <p class="wp-block-paragraph">11.12.2026<br><strong>Konzert: Lemur „Yeniden“ Record Release Concert</strong></p>
            </div>
            """.trimIndent()
        SlaughterhouseOverviewPageScraper()
            .scrape(Jsoup.parse(html, baseUrl), baseUrl)
            .single()
            .artists
            .map { it.name } shouldBe listOf("Lemur")
    }

    @Test
    fun `bills the live acts an entry names over the series before its title's colon`() {
        on(11, 14).title shouldBe "Aufnahme+Wiedergabe: Sutcliffe No More / Control / Kommando"
        billing(11, 14) shouldBe
            listOf("Sutcliffe No More" to ArtistRole.HEADLINER, "Control" to ArtistRole.HEADLINER, "Kommando" to ArtistRole.HEADLINER)
        on(11, 14).artists.none { it.titleDerived } shouldBe true
    }

    @Test
    fun `bills a house programme's concert and party lines, not the night's name`() {
        billing(12, 5) shouldBe
            listOf(
                "Without Shrimps" to ArtistRole.HEADLINER,
                "WR51" to ArtistRole.HEADLINER,
                "Wildberries" to ArtistRole.HEADLINER,
                "Mark Reeder" to ArtistRole.DJ
            )
    }

    @Test
    fun `bills a paragraph of bare names over a title of series names`() {
        billing(12, 12) shouldBe
            listOf("Bad Sector" to ArtistRole.HEADLINER, "Inade" to ArtistRole.HEADLINER, "Phelios" to ArtistRole.HEADLINER)
    }

    @Test
    fun `reads the DJs under an on decks line and a bare djs line`() {
        billing(11, 6) shouldBe
            listOf("Uwe Marx", "Marko König", "Ben Bloodygrave", "Oberst Panizza", "Ecate Styx").map { it to ArtistRole.DJ }
        billing(11, 7) shouldBe listOf("terrorwave", "karocky", "paura diamante").map { it to ArtistRole.DJ }
    }

    @Test
    fun `keeps the title's act when a paragraph of names is a sentence, a field or a DJ list`() {
        val html =
            """
            <div class="entry-content">
            <p class="wp-block-paragraph">13.11.2026<br><strong>Konzert+Party: Tyske Ludder</strong></p>
            <p class="wp-block-paragraph">Beginn 22 Uhr<br>Abendkasse</p>
            <p class="wp-block-paragraph">Nur begrenzte Tickets!<br>Sichert euch eure Tickets!</p>
            <p class="wp-block-paragraph">djs<br>terrorwave<br>karocky</p>
            </div>
            """.trimIndent()
        val night = SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).single()
        night.artists.map { it.name to it.role } shouldBe
            listOf("Tyske Ludder" to ArtistRole.HEADLINER, "terrorwave" to ArtistRole.DJ, "karocky" to ArtistRole.DJ)
    }

    @Test
    fun `reads event 13953's time-first doors and free entry from the November page`() {
        val night = november.single { it.eventDate == LocalDate.of(2026, 11, 20) }
        night.doorsTime shouldBe LocalTime.of(19, 0)
        night.startTime.shouldBeNull()
        night.free shouldBe true
        night.pricePresale.shouldBeNull()
        night.priceBoxOffice.shouldBeNull()
        night.description!! shouldNotContain "Einlass"
        night.description shouldNotContain "Eintritt frei"
    }

    @Test
    fun `reads event 13956's doors and start on one line, and its VVK and Abendkasse in Euro`() {
        val night = november.single { it.eventDate == LocalDate.of(2026, 12, 11) }
        night.doorsTime shouldBe LocalTime.of(20, 0)
        night.startTime shouldBe LocalTime.of(21, 0)
        night.pricePresale shouldBe BigDecimal("12")
        night.priceBoxOffice shouldBe BigDecimal("15")
        night.free shouldBe false
        night.description.orEmpty() shouldNotContain "VVK"
        night.description.orEmpty() shouldNotContain "Beginn"
        val indie = november.single { it.eventDate == LocalDate.of(2026, 11, 27) }
        indie.pricePresale shouldBe BigDecimal("7")
        indie.priceBoxOffice shouldBe BigDecimal("10")
    }

    @Test
    fun `reads events 13954 to 13956's bare tixforgigs link under the price line as the ticket link`() {
        november.single { it.eventDate == LocalDate.of(2026, 11, 27) }.ticketUrl shouldBe
            "https://www.tixforgigs.com/de-de/Event/78286/34-lange-nacht-der-indieschoenheiten-slaughterhouse-berlin"
        november.single { it.eventDate == LocalDate.of(2026, 12, 4) }.ticketUrl shouldBe
            "https://www.tixforgigs.com/de-de/Event/78287/santas-riot-vi-live-deshurance-the-irony-ravenpath-slaughterhouse-berlin"
        november.single { it.eventDate == LocalDate.of(2026, 12, 11) }.ticketUrl shouldBe
            "https://www.tixforgigs.com/de-de/Event/78291/lemur-yeniden-record-release-concert-slaughterhouse-berlin"
        listOf(LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 4), LocalDate.of(2026, 12, 11)).forEach { date ->
            november.single { it.eventDate == date }.description!! shouldNotContain "tixforgigs"
        }
        november.single { it.eventDate == LocalDate.of(2026, 11, 27) }.description!! shouldStartWith "34. Lange Nacht der Indieschoenheit"
    }

    @Test
    fun `takes a labelled ticket link over a bare shop link, and no bare link that is not a shop's`() {
        val labelled =
            entryOf(
                "Konzert: Tyske Ludder",
                """<a href="https://www.tixforgigs.com/Event/1">shop</a><br>Tickets: <a href="https://www.eventim-light.com/de/a/2/">eventim</a>"""
            )
        labelled.ticketUrl shouldBe "https://www.eventim-light.com/de/a/2/"
        val bare =
            entryOf(
                "Konzert: Tyske Ludder",
                """<a href="https://tyskeludder.de/">tyskeludder.de</a><br><a href="https://www.instagram.com/tyskeludder/">Instagram</a>"""
            )
        bare.ticketUrl.shouldBeNull()
        bare.description!! shouldContain "tyskeludder.de"
        val sentence = entryOf("Konzert: Tyske Ludder", "Karten bei https://www.tixforgigs.com/Event/4 und an der Abendkasse.")
        sentence.description!! shouldContain "tixforgigs.com/Event/4"
        val redirect = "https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.tixforgigs.com%2FEvent%2F3&amp;h=x"
        val wrapped = entryOf("Konzert: Tyske Ludder", """<a href="$redirect">tixforgigs.com</a>""")
        wrapped.ticketUrl shouldBe "https://www.tixforgigs.com/Event/3"
    }

    @Test
    fun `keeps every time the November page's older entries name`() {
        november.single { it.eventDate == LocalDate.of(2026, 11, 28) }.doorsTime shouldBe LocalTime.of(22, 0)
        november.single { it.eventDate == LocalDate.of(2026, 11, 28) }.startTime shouldBe LocalTime.of(22, 0)
        november.single { it.eventDate == LocalDate.of(2026, 12, 12) }.startTime shouldBe LocalTime.of(21, 0)
        november.single { it.eventDate == LocalDate.of(2026, 12, 19) }.startTime shouldBe LocalTime.of(22, 0)
        november.single { it.eventDate == LocalDate.of(2026, 12, 31) }.startTime shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `reads a time-first line of either keyword, alone or in either order, and over midnight`() {
        timesOf("21:00 Beginn") shouldBe (null to LocalTime.of(21, 0))
        timesOf("19 Uhr Einlass") shouldBe (LocalTime.of(19, 0) to null)
        timesOf("Einlass: 19:30") shouldBe (LocalTime.of(19, 30) to null)
        timesOf("23:00 Einlass - 0:30 Beginn") shouldBe (LocalTime.of(23, 0) to LocalTime.of(0, 30))
        timesOf("21.00 Beginn / 20.00 Einlass") shouldBe (LocalTime.of(20, 0) to LocalTime.of(21, 0))
        timesOf("Die Band spielt um 20:00 Beginn der Tour") shouldBe (null to null)
    }

    @Test
    fun `reads a price line's presale and box office in euro signs and words, and leaves a bare Abendkasse unpriced`() {
        priceOf("VVK 7€ (+Gebühren) / Abendkasse 10€") shouldBe Triple(BigDecimal("7"), BigDecimal("10"), false)
        priceOf("VVK 12 Euro (+Gebühren) / Abendkasse 15 Euro") shouldBe Triple(BigDecimal("12"), BigDecimal("15"), false)
        priceOf("VVK 9,50 €") shouldBe Triple(BigDecimal("9.50"), null, false)
        priceOf("Abendkasse 8 Euro") shouldBe Triple(null, BigDecimal("8"), false)
        priceOf("Eintritt: 10€") shouldBe Triple(null, BigDecimal("10"), false)
        priceOf("Eintritt frei") shouldBe Triple(null, null, true)
        priceOf("Abendkasse") shouldBe Triple(null, null, false)
    }

    @Test
    fun `keeps the time and price of an entry with a long title or a cancelled or sold-out marker`() {
        val title = "Konzert: " + "Sehr Lange Nacht der Indieschönheiten ".repeat(8).trim()
        for (heading in listOf(title, "Konzert: ABGESAGT – Tyske Ludder", "Konzert: Tyske Ludder (ausverkauft)", "Konzert: VERSCHOBEN: Tyske Ludder")) {
            val night = entryOf(heading, "20:00 Einlass – 21:00 Beginn<br>VVK 12 Euro (+Gebühren) / Abendkasse 15 Euro")
            night.doorsTime shouldBe LocalTime.of(20, 0)
            night.startTime shouldBe LocalTime.of(21, 0)
            night.pricePresale shouldBe BigDecimal("12")
            night.priceBoxOffice shouldBe BigDecimal("15")
        }
        entryOf("Konzert: Tyske Ludder (ausverkauft)", "19:00 Einlass").soldOut shouldBe true
    }

    private fun entryOf(
        heading: String,
        body: String
    ) = SlaughterhouseOverviewPageScraper()
        .scrape(
            Jsoup.parse(
                """
                <div class="entry-content">
                <p class="wp-block-paragraph">13.11.2026<br><strong>$heading</strong></p>
                <p class="wp-block-paragraph">$body</p>
                </div>
                """.trimIndent(),
                baseUrl
            ),
            baseUrl
        ).single()

    private fun timesOf(line: String) = entryOf("Konzert: Tyske Ludder", line).let { it.doorsTime to it.startTime }

    private fun priceOf(line: String) = entryOf("Konzert: Tyske Ludder", line).let { Triple(it.pricePresale, it.priceBoxOffice, it.free) }

    @Test
    fun `yields nothing for a page without the post`() {
        SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
