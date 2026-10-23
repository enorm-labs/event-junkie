package de.norm.events.scraper.kulturhausspandau

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** The `/wp-json/eventmanager/v1/events` response saved on 2026-10-23: 250 events, past ones included. */
fun kulturhausSpandauEvents(): String =
    KulturhausSpandauApiScraperTest::class.java.classLoader
        .getResourceAsStream("scraper/kulturhausspandau/kulturhausspandau-events.json")!!
        .bufferedReader()
        .readText()

class KulturhausSpandauApiScraperTest {
    private val events = KulturhausSpandauApiScraper().scrape(kulturhausSpandauEvents(), SOURCE_URL)
    private val upcoming = events.filterNot { it.eventDate.isBefore(LocalDate.of(2026, 10, 23)) }

    private fun event(id: Long) = events.single { it.sourceId == "kulturhaus_spandau:$id" }

    @Test
    fun `keeps the house's own rooms and its stage programme`() {
        upcoming shouldHaveSize 38
        events.map { it.sourceId }.toSet().size shouldBe events.size
    }

    @Test
    fun `reads a comedy night fully, with the sold-out tail cut from the title`() {
        val grammel = event(17165)

        grammel.title shouldBe "LACHEN TUT GUT(es)!"
        grammel.subtitle shouldBe "Sascha Grammel"
        grammel.eventType shouldBe EventType.COMEDY.name
        grammel.eventDate shouldBe LocalDate.of(2026, 11, 10)
        grammel.startTime shouldBe LocalTime.of(20, 0)
        grammel.endTime shouldBe LocalTime.of(22, 0)
        grammel.room shouldBe "Theatersaal"
        grammel.soldOut shouldBe true
        grammel.pricePresale shouldBe BigDecimal("30")
        grammel.ticketUrl shouldBe "https://www.eventbrite.de/e/sascha-grammel-lachen-tut-gutes-tickets-1984131136641"
        grammel.artists.map { it.name } shouldContainExactly listOf("Sascha Grammel")
    }

    @Test
    fun `types by category, a slam as a reading and a staged concert as a show`() {
        event(2648).eventType shouldBe EventType.READING.name
        event(19148).eventType shouldBe EventType.CONCERT.name
        event(766).eventType shouldBe EventType.SHOW.name
        event(14714).eventType shouldBe EventType.OTHER.name
        event(14714).typeIsFallback shouldBe true
    }

    @Test
    fun `keeps a free concert's price line as the note`() {
        val handel = event(12533)

        handel.free shouldBe true
        handel.pricePresale.shouldBeNull()
        handel.priceNote shouldBe "Eintritt frei | Spenden willkommen"
        handel.room.shouldBeNull()
    }

    @Test
    fun `drops the Zitadelle and Altstadt events, tours, exhibitions, children's shows and the sing-along`() {
        val titles = upcoming.map { it.title }

        titles shouldNotContain "Gambenconsort Phantasm"
        titles shouldNotContain "Blindmalen mit Künstler Aydin Öztek."
        titles shouldNotContain "Wind im Gummistiefel"
        titles shouldNotContain "Hops, der Oberkellner"
        titles shouldNotContain "Das Mitsingding"
    }

    @Test
    fun `strips the editor's hidden tag from a title`() {
        events.filter { "<p" in it.title || "hidden" in it.title }.shouldBeEmpty()
    }

    @Test
    fun `joins the slam's bare place to its kicker and lifts a show's name over its list of disciplines`() {
        event(2648).title shouldBe "Poetry Slam Spandau"
        event(2648).subtitle.shouldBeNull()
        event(14214).title shouldBe "This is Halloween"
        event(14214).subtitle shouldBe "Live-Gesang | Tanz | Artistik"
    }

    private companion object {
        const val SOURCE_URL = "https://kulturhaus-spandau.de/wp-json/eventmanager/v1/events"
    }
}
