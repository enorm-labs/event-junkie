package de.norm.events.scraper.orwohaus

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Unit tests for [OrwohausCalendarScraper], against a saved copy of the real `/events.ics` feed. */
class OrwohausCalendarScraperTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = OrwohausCalendarScraper(clock)

    private val events: List<ScrapedEvent> by lazy {
        scraper.scrape(
            javaClass.classLoader
                .getResourceAsStream("scraper/orwohaus/orwohaus-events.ics")!!
                .bufferedReader()
                .readText()
        )
    }

    private fun event(slug: String) = events.single { it.sourceId == "orwohaus:$slug" }

    @Test
    fun `scrape reads every event within a year and skips the workshops`() {
        events shouldHaveSize 34
        events.count { it.title == "Jamsession im ORWOhaus" } shouldBe 26
        events.none { it.title.startsWith("Workshop") } shouldBe true
        events.maxOf { it.eventDate } shouldBe LocalDate.of(2027, 10, 9)
    }

    @Test
    fun `scrape reads the site's times as a fixed UTC+1, also in summer`() {
        val festival = event("orwohaus-festival-xx-das-jubilaeum")

        festival.eventDate shouldBe LocalDate.of(2027, 8, 12)
        festival.startTime shouldBe LocalTime.of(17, 0)
        festival.endDate shouldBe LocalDate.of(2027, 8, 15)
        festival.endTime shouldBe LocalTime.of(2, 0)
        festival.eventType shouldBe "FESTIVAL"
        festival.genre.shouldBeNull()
        festival.artists.shouldBeEmpty()
    }

    @Test
    fun `scrape maps a concert with its room, genre, poster, prose times and title acts`() {
        val event = event("droelfzehn-bier-vol-015-mit-kackbratze-artikel-5-und-feels-empty")

        event.title shouldBe "Drölfzehn Bier vol. 0,15% mit Kackbratze, Artikel 5 und FEELS EMPTY."
        event.eventDate shouldBe LocalDate.of(2026, 10, 17)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.endDate shouldBe LocalDate.of(2026, 10, 17)
        event.endTime shouldBe LocalTime.of(23, 45)
        event.room shouldBe "Showroom 104"
        event.genre shouldBe "Punk"
        event.eventType shouldBe "CONCERT"
        event.sourceUrl shouldBe "https://www.orwohaus.de/events/droelfzehn-bier-vol-015-mit-kackbratze-artikel-5-und-feels-empty/"
        event.description.shouldNotBeNull() shouldContain "Wir sind zurück aus der Sommerpause!"
        event.artists.map { it.name } shouldContainExactly listOf("Kackbratze", "Artikel 5", "FEELS EMPTY.")
    }

    @Test
    fun `scrape reads the labelled prices and the image attachment`() {
        val event = event("necromania-2026")

        event.pricePresale shouldBe BigDecimal("40")
        event.priceBoxOffice shouldBe BigDecimal("50")
        event.imageUrl shouldBe "https://www.orwohaus.de/wp-content/uploads/2026/04/Titelbild-ORWO-scaled.jpg"
        event.startTime shouldBe LocalTime.of(14, 0)
        event.endDate shouldBe LocalDate.of(2026, 10, 18)
        event.endTime shouldBe LocalTime.of(1, 0)
        event.room shouldBe "OHA! - Veranstaltungshalle"
        event.genre shouldBe "Metal"
    }

    @Test
    fun `a series or house night bills no act, a tour title bills its act`() {
        event("necromania-2026").artists.shouldBeEmpty()
        event("be-noise-live-battle-vorrunde-3-4").artists.shouldBeEmpty()
        event("halloween-im-orwohaus-2").artists.shouldBeEmpty()
        event("jamsession-im-orwohaus-2026-10-24").artists.shouldBeEmpty()
        event("arsen-fahrtenbuch-clubtour-2026").artists.map { it.name } shouldContainExactly listOf("ARSEN")
        event("12299").artists.map { it.name } shouldContainExactly listOf("SERIOUS BLACK")
    }

    @Test
    fun `a jam session is a concert in the room the house names, with no location of its own`() {
        val jam = event("jamsession-im-orwohaus-2026-10-24")

        jam.eventType shouldBe "CONCERT"
        jam.startTime shouldBe LocalTime.of(21, 0)
        jam.room.shouldBeNull()
        jam.genre.shouldBeNull()
        jam.description.shouldNotBeNull() shouldStartWith "Alle 2 Wochen sonnabends"
    }

    @Test
    fun `a room named only by the house is no room`() {
        event("halloween-im-orwohaus-2").room.shouldBeNull()
    }

    @Test
    fun `an entry without a URL is skipped and an all-day date carries no time`() {
        val calendar =
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            DTSTART:20261101T190000Z
            SUMMARY:No page
            END:VEVENT
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20261102
            URL:https://www.orwohaus.de/events/all-day/
            SUMMARY:Tag der offenen Tür
            CATEGORIES:Eigene Veranstaltungen
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()

        val parsed = scraper.scrape(calendar).single()

        parsed.title shouldBe "Tag der offenen Tür"
        parsed.eventDate shouldBe LocalDate.of(2026, 11, 2)
        parsed.startTime.shouldBeNull()
        parsed.eventType shouldBe "OTHER"
    }
}
