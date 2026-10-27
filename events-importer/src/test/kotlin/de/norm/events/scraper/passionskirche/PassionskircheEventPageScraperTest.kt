package de.norm.events.scraper.passionskirche

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.isFestivalTitle
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class PassionskircheEventPageScraperTest {
    private val scraper = PassionskircheEventPageScraper()

    private fun scrape(slug: String) =
        scraper.scrape(Jsoup.parse(fixture("passionskirche-event-$slug.html"), "https://www.halle-luja.berlin/$slug"), "https://www.halle-luja.berlin/$slug")

    @Test
    fun `reads the schedule block, the text and the ticket link`() {
        val event = scrape("wintertales-from-around-the-world").shouldNotBeNull()

        event.title shouldBe "Wintertales - from around the world"
        event.eventDate shouldBe LocalDate.of(2026, 12, 20)
        event.doorsTime shouldBe LocalTime.of(17, 0)
        event.startTime shouldBe LocalTime.of(18, 0)
        event.ticketUrl!! shouldStartWith "https://eventfrog.de/"
        event.imageUrl!! shouldStartWith "https://www.halle-luja.berlin/-_-/res/"
        event.sourceId shouldBe "passionskirche:wintertales-from-around-the-world"
        val description = event.description.shouldNotBeNull()
        description shouldStartWith "Wenn das Jahr zur Ruhe kommt"
        description shouldNotContain "Ablauf"
        description shouldNotContain "Onlinetickets"
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `reads a Konzertablauf block and bills no act the text spells differently`() {
        val event = scrape("everytime-we-touch-tour-2026").shouldNotBeNull()

        event.eventDate shouldBe LocalDate.of(2026, 10, 27)
        event.startTime shouldBe LocalTime.of(18, 0)
        event.doorsTime.shouldBeNull()
        event.ticketUrl!! shouldContain "eventim.de"
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `reads a block with only a doors time`() {
        val event = scrape("the-dublin-legends").shouldNotBeNull()

        event.eventDate shouldBe LocalDate.of(2026, 12, 4)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime.shouldBeNull()
        event.artists.map { it.name } shouldContainExactly listOf("THE DUBLIN LEGENDS")
    }

    @Test
    fun `bills the headliner and the support the text names`() {
        val event = scrape("rita-wilson").shouldNotBeNull()

        event.eventDate shouldBe LocalDate.of(2026, 11, 6)
        event.doorsTime shouldBe LocalTime.of(18, 0)
        event.startTime shouldBe LocalTime.of(19, 0)
        event.artists.map { it.name to it.role } shouldContainExactly listOf("RITA WILSON" to ArtistRole.HEADLINER, "ANNA LEANNE" to ArtistRole.SUPPORT)
    }

    @Test
    fun `bills no act for a festival title, which the import types`() {
        val event = scrape("irish-folk-festival-2026").shouldNotBeNull()

        event.eventDate shouldBe LocalDate.of(2026, 11, 20)
        event.typeIsFallback shouldBe true
        isFestivalTitle(event.title) shouldBe true
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `leaves the date unresolved without a schedule block`() {
        val html = """<main><h1>Band</h1><div class="text-root"><p>Ein Abend.</p></div></main>"""
        val event = scraper.scrape(Jsoup.parse(html, "https://www.halle-luja.berlin/band"), "https://www.halle-luja.berlin/band").shouldNotBeNull()

        event.eventDate shouldBe UNRESOLVED_EVENT_DATE
        event.description shouldBe "Ein Abend."
        scraper.scrape(Jsoup.parse("<main></main>", "https://www.halle-luja.berlin/x"), "https://www.halle-luja.berlin/x").shouldBeNull()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/passionskirche/$name")!!
            .bufferedReader()
            .readText()
}
