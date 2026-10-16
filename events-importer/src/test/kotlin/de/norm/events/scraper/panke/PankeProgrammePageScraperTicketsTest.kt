package de.norm.events.scraper.panke

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/**
 * Ticket-link and `feat.` tests for [PankeProgrammePageScraper] (#3377).
 *
 * The fixture is three upcoming articles of the 2026-10-16 page. Bassism names its acts after
 * `feat.` and prints `Tickets: <link>`. FUKSTEP prints a `Tickets on RA` paragraph with no link
 * before its `TICKETS: <link>`. Herr Kuchen prints no ticket paragraph.
 */
class PankeProgrammePageScraperTicketsTest {
    private val scraper = PankeProgrammePageScraper()
    private val sourceUrl = "https://www.pankeculture.com/programme/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/panke/panke-programme-tickets.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `reads the link of a tickets paragraph`() {
        event("panke:17489").ticketUrl shouldBe "https://ra.co/events/2514638"
    }

    @Test
    fun `skips a tickets paragraph without a link for the one that has it`() {
        event("panke:17492").ticketUrl shouldBe "https://ra.co/events/2501586"
    }

    @Test
    fun `stores no ticket link for a night without a tickets paragraph`() {
        event("panke:17504").ticketUrl.shouldBeNull()
    }

    @Test
    fun `bills the acts a title names after feat, not the night's name`() {
        val bassism = event("panke:17489")
        bassism.artists.map { it.name } shouldContainExactly listOf("N-Type", "Slowie")
        bassism.artists.all { it.titleDerived } shouldBe true
        // The body says "all night long": a club night, whatever the title bills.
        bassism.eventType shouldBe EventType.PARTY.name
    }

    @Test
    fun `a title without feat bills no act from the title`() {
        event("panke:17492").artists.shouldBeEmpty()
    }

    @Test
    fun `reads each label form the venue prints`() {
        val forms =
            listOf(
                "Tickets: <a href=\"https://ra.co/events/1\">https://ra.co/events/1</a>",
                "TICKETS: <a href=\"https://ra.co/events/1\">https://ra.co/events/1</a>",
                "Tickets on RA: <a href=\"https://ra.co/events/1\">https://ra.co/events/1</a>",
                "Tickets <a href=\"https://ra.co/events/1\">https://ra.co/events/1</a>",
                "Tickets: <a href=\"https://ra.co/events/1\">here</a>"
            )
        forms.map { pankeTicketUrl(paragraphs(it)) }.distinct() shouldContainExactly listOf("https://ra.co/events/1")
    }

    @Test
    fun `takes any seller's link`() {
        pankeTicketUrl(paragraphs("Tickets: <a href=\"https://www.tixforgigs.com/Event/1\">Tixforgigs</a>")) shouldBe
            "https://www.tixforgigs.com/Event/1"
    }

    @Test
    fun `ignores a link that prose separates from the label, and a mail link`() {
        pankeTicketUrl(paragraphs("Tickets at the door, more on <a href=\"https://instagram.com/panke\">Instagram</a>")).shouldBeNull()
        pankeTicketUrl(paragraphs("Tickets: <a href=\"mailto:tickets@example.org\">tickets@example.org</a>")).shouldBeNull()
        pankeTicketUrl(paragraphs("More on <a href=\"https://ra.co/events/1\">RA</a>")).shouldBeNull()
    }

    @Test
    fun `featured acts follow feat or ft and nothing else`() {
        featuredActs("Bassism 5.0 feat. N-Type & Slowie").map { it.name } shouldContainExactly listOf("N-Type", "Slowie")
        featuredActs("Dub Night ft. Mista Baptiste").map { it.name } shouldContainExactly listOf("Mista Baptiste")
        featuredActs("FUKSTEP").shouldBeEmpty()
        featuredActs("Featherweight Disko").shouldBeEmpty()
    }

    private fun paragraphs(vararg html: String) = Jsoup.parse(html.joinToString("") { "<p>$it</p>" }, sourceUrl).select("p")
}
