package de.norm.events.scraper.tempodrom

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

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

    private fun fallbackRow(
        title: String,
        subtitle: String?
    ) = listingRow.copy(title = title, subtitle = subtitle, eventType = "CONCERT", typeIsFallback = true)

    private fun descriptionPage(blurb: String) =
        Jsoup.parse("""<button class="acc-button btn" aria-controls="event-txt-1">Beschreibung <span></span></button><div id="event-txt-1"><p>$blurb</div>""")

    private fun page(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/tempodrom/$name")!!
                .bufferedReader()
                .readText(),
            listingRow.sourceUrl
        )

    @Test
    fun `reads the promoter and its website from the Veranstalter credit`() {
        val event = scraper.addPromoter(listingRow, page("tempodrom-detail-trinity-music.html"))

        event.promoters shouldBe listOf("Trinity Music GmbH")
        event.promoterWebsites shouldBe mapOf("Trinity Music GmbH" to "http://www.trinitymusic.de")
    }

    @Test
    fun `reads a second promoter from another page`() {
        val event = scraper.addPromoter(listingRow, page("tempodrom-detail-d2mberlin.html"))

        event.promoters shouldBe listOf("d2mberlin GmbH")
        event.promoterWebsites shouldBe mapOf("d2mberlin GmbH" to "http://www.d2mberlin.de")
    }

    @Test
    fun `never reads the media partner the blurb names`() {
        // The Jill Scott page says "präsentiert von Radio Eins" in its blurb (#2653).
        scraper.addPromoter(listingRow, page("tempodrom-detail-trinity-music.html")).promoters shouldBe listOf("Trinity Music GmbH")
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
        val event = scraper.enrich(fallbackRow("SHADEN (أنا إمرأة تعبانة) Show", "// Berlin"), page("tempodrom-detail-shaden-comedy.html"))

        event.eventType shouldBe "COMEDY"
        event.typeIsFallback shouldBe false
        event.promoters shouldBe listOf("Levanto Entertainment")
    }

    @Test
    fun `keeps a fallback CONCERT whose only comedy is the promoter's name`() {
        // Abishek Kumar's page credits "The Comedy Cornershop" and its blurb names no comedy.
        val row = fallbackRow("Abishek Kumar", "Abishek wants to Enjoy Life! - Europe/UK tour 2026")

        val event = scraper.enrich(row, page("tempodrom-detail-comedy-promoter-no-cue.html"))

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
    fun `leaves the event as the listing gave it when the page credits no promoter`() {
        val document = Jsoup.parse("<div class=copy>Event teilen</div><p>präsentiert von Radio Eins</p>", listingRow.sourceUrl)

        scraper.addPromoter(listingRow, document) shouldBeSameInstanceAs listingRow
    }
}
