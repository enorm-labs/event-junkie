package de.norm.events.scraper.acudmachtneu

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalTime

class AcudMachtNeuEventPageScraperTest {
    private val scraper = AcudMachtNeuEventPageScraper()

    private fun scrape(
        name: String,
        url: String
    ) = scraper.scrape(document(name, url), url)!!

    @Test
    fun `reads the room and time from the header, the text, the picture and the presale link`() {
        val event = scrape("acudmachtneu-event-burre.html", "https://acudmachtneu.de/events/2390/burre-w-heavenphetamine")

        event.title shouldBe "Burre w/ Heavenphetamine"
        event.sourceId shouldBe "acud_macht_neu:2390"
        event.room shouldBe "Club"
        event.startTime shouldBe LocalTime.of(20, 0)
        event.doorsTime.shouldBeNull()
        event.imageUrl shouldBe "https://acudmachtneu.de/resources/uploads/2026/10/blurred-frame-3.jpg"
        event.ticketUrl shouldBe "https://ra.co/events/2554847"
        event.description!! shouldContain "HEAVENPHETAMINE is coming back to Berlin"
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `prefers a labelled start in the text over the header's time and keeps a price without an amount as a note`() {
        val event = scrape("acudmachtneu-event-ukrainian-party.html", "https://acudmachtneu.de/events/2381/ukrainian-film-festival-party")

        event.startTime shouldBe LocalTime.of(22, 0)
        event.priceNote shouldBe "Donation Based"
        event.priceBoxOffice.shouldBeNull()
        event.ticketUrl.shouldBeNull()
    }

    @Test
    fun `bills the acts of the line-up paragraph, one per line, split at a b2b`() {
        val event = scrape("acudmachtneu-event-syrup.html", "https://acudmachtneu.de/events/2389/syrup")

        event.artists.map { it.name } shouldContainExactly
            listOf("James Lotion", "maniaclina", "Charlotte Lion", "Dzoara", "Housekatze", "Keta Perry")
    }

    @Test
    fun `bills a festival's acts written on one line between slashes`() {
        // Event 12148.
        val url = "https://acudmachtneu.de/events/2395/8mm-fest-2"
        val html =
            """
            <article class="event-article">
              <h1 class="t-title-header--event">8MM Fest</h1>
              <div class="event-article__content">
                <p>Line up :<br />
                ABIGAIL TOLL / BLACK BORDELLO /CALVIN LOVE / FADE EVARE /I WANT POETRY / MAKARA  / AC/DC / JAAKKO EINO KALEVI + MORE</p>
              </div>
            </article>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, url), url)!!.artists.map { it.name } shouldContainExactly
            listOf("ABIGAIL TOLL", "BLACK BORDELLO", "CALVIN LOVE", "FADE EVARE", "I WANT POETRY", "MAKARA", "AC/DC", "JAAKKO EINO KALEVI + MORE")
    }

    @Test
    fun `bills the acts under a qualified heading without their crew tags`() {
        val event = scrape("acudmachtneu-event-astral-lab.html", "https://acudmachtneu.de/events/2398/astral-lab-x-quake-acud")

        event.artists.map { it.name } shouldContainExactly
            listOf(
                "FILTH.y",
                "FRØSA (Sige Alarcon & Frølar)",
                "Hassdenteufel",
                "Hayaku",
                "Jeroen",
                "pnk pnthr",
                "Seahorse (DJ Seaview & Wendy)",
                "vSto"
            )
    }

    @Test
    fun `keeps a name that is all brackets or has no space before one, and reads a heading with or without a qualifier`() {
        val tags =
            scrapeContent(
                "Line up:<br />[ˈnʊl]<br />Kid[x]<br />Ayla Nightingale-Moreau and the Very Long Name Ensemble [Quake Collective Copenhagen]"
            )
        val bare = scrapeContent("Lineup<br />Jeroen [Quake]")
        val qualifiedOnItsOwnLine = scrapeContent("Lineup (alphabetical)<br />Hayaku")
        val actInBrackets = scrapeContent("Line up (Hayaku) Jeroen")

        tags.artists.map { it.name } shouldContainExactly
            listOf("[ˈnʊl]", "Kid[x]", "Ayla Nightingale-Moreau and the Very Long Name Ensemble")
        bare.artists.map { it.name } shouldContainExactly listOf("Jeroen")
        qualifiedOnItsOwnLine.artists.map { it.name } shouldContainExactly listOf("Hayaku")
        actInBrackets.artists.map { it.name } shouldContainExactly listOf("(Hayaku) Jeroen")
    }

    @Test
    fun `reads doors equal to the header's time as the start, a price range as a note, and the act before a track`() {
        val event = scrape("acudmachtneu-event-renatura.html", "https://acudmachtneu.de/events/2385/renatura-06-%e2%80%93-hexapoda-release-event")

        event.startTime shouldBe LocalTime.of(19, 0)
        event.doorsTime.shouldBeNull()
        event.priceNote shouldBe "15 - 18 €"
        event.ticketUrl shouldBe "https://www.eventbrite.com/e/releaseparty-renatura-06-hexapoda-tickets-2002290259065"
        event.artists.map { it.name } shouldContainExactly listOf("Mark Ijzerman", "Pléyides")
    }

    @Test
    fun `reads a door price after an entrance fee, a tickets label in the venue's 16E spelling, and a price at the door`() {
        // Events 12150, 12147 and 12149.
        val prices =
            listOf(
                "<strong>Entrance fee:</strong> €10<br />Tickets via Resident Advisor &amp; at the door.",
                "<strong>Presale:</strong><a href=\"https://ra.co/events/1\"> https://ra.co/events/1</a><br />Tickets: 16E",
                "<strong>Ticket presale:</strong><br /><a href=\"https://ra.co/events/1\">https://ra.co/events/1</a><br />Price at the door will be 15€"
            ).map { scrapeContent(it).priceBoxOffice?.toPlainString() }

        prices shouldContainExactly listOf("10", "16", "15")
    }

    @Test
    fun `keeps a free-entry line as the note, and no tickets link as a price`() {
        // Event 11964.
        val free = scrapeContent("<strong>SA 5.11, 21H ACUD CLUB</strong><br />FREE ENTRY TO THE PARTY!")
        val link = scrapeContent("Tickets: <a href=\"https://ra.co/events/1\">https://ra.co/events/1</a>")

        free.priceNote shouldBe "FREE ENTRY TO THE PARTY!"
        free.priceBoxOffice.shouldBeNull()
        link.priceNote.shouldBeNull()
        link.priceBoxOffice.shouldBeNull()
    }

    @Test
    fun `reads a door price written before at the door in the venue's 28e spelling`() {
        // Event 12146.
        val event = scrape("acudmachtneu-event-hyper-fog.html", "https://acudmachtneu.de/events/2394/hyper-fog")

        event.priceBoxOffice?.toPlainString() shouldBe "28"
        event.priceNote.shouldBeNull()
    }

    @Test
    fun `reads the amount next to at the door, keeps a sliding scale as the note, and reads no word or year after a number as euros`() {
        val presaleAndDoor = scrapeContent("Presale 12e, 15e at the door")
        val slidingScale = scrapeContent("10-15e at the door")
        val noAmount = scrapeContent("Tickets via Resident Advisor &amp; at the door.")
        val words = listOf("Tickets: 3 Ebenen", "Tickets: 2026 E-Werk", "Doors at 9pm").map(::scrapeContent)

        presaleAndDoor.priceBoxOffice?.toPlainString() shouldBe "15"
        slidingScale.priceBoxOffice.shouldBeNull()
        slidingScale.priceNote shouldBe "10-15 €"
        noAmount.priceBoxOffice.shouldBeNull()
        noAmount.priceNote.shouldBeNull()
        words.map { it.priceBoxOffice } shouldContainExactly listOf(null, null, null)
        words.map { it.priceNote } shouldContainExactly listOf(null, null, null)
    }

    @Test
    fun `reads a ticket link after a presale link or ticket link label, and a link that reads presale only`() {
        // Events 12146, 12150 and 12151.
        val presaleLink = scrape("acudmachtneu-event-hyper-fog.html", "https://acudmachtneu.de/events/2394/hyper-fog")
        val ticketLink = scrape("acudmachtneu-event-kholektiv.html", "https://acudmachtneu.de/events/2396/kholektiv-showcase-with-lola-kay")
        val presaleOnly = scrape("acudmachtneu-event-sunday-school.html", "https://acudmachtneu.de/events/2397/sunday-school-4th-anniversary")

        presaleLink.ticketUrl shouldBe "https://ra.co/events/2565139"
        ticketLink.ticketUrl shouldBe "https://de.ra.co/events/2568916"
        presaleOnly.ticketUrl shouldBe "https://ra.co/events/2564610"
    }

    @Test
    fun `reads no ticket link after another link label or from a link whose text only mentions tickets`() {
        val infoLink = scrapeContent("""more info link: <a href="https://example.de/info">https://example.de/info</a>""")
        val prose = scrapeContent("""<a href="https://example.de/kino">Tickets und Infos</a>""")
        val labelFirst =
            scrapeContent(
                """<a href="https://example.de/shop">PRESALE ONLY</a><br>Tickets: <a href="https://example.de/ra">https://example.de/ra</a>"""
            )

        infoLink.ticketUrl.shouldBeNull()
        prose.ticketUrl.shouldBeNull()
        labelFirst.ticketUrl shouldBe "https://example.de/ra"
    }

    @Test
    fun `reads the gallery as the room of an exhibition`() {
        val event = scrape("acudmachtneu-event-orbital-decay.html", "https://acudmachtneu.de/events/2379/orbital-decay")

        event.room shouldBe "Galerie"
        event.startTime shouldBe LocalTime.of(18, 0)
    }

    @Test
    fun `returns null for a page without an event article`() {
        val url = "https://acudmachtneu.de/events/1/gone"
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", url), url).shouldBeNull()
    }

    private fun scrapeContent(paragraph: String): ScrapedEvent {
        val url = "https://acudmachtneu.de/events/1/test"
        val html =
            """<article class="event-article"><h1 class="t-title-header--event">Test</h1>""" +
                """<div class="event-article__content"><p>$paragraph</p></div></article>"""
        return scraper.scrape(Jsoup.parse(html, url), url)!!
    }

    private fun document(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/acudmachtneu/$name")!!
            .bufferedReader()
            .readText(),
        url
    )
}
