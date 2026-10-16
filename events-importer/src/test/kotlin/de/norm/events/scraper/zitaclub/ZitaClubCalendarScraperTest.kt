package de.norm.events.scraper.zitaclub

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [ZitaClubCalendarScraper], against a saved copy of the real `/events.ics` feed. */
class ZitaClubCalendarScraperTest {
    private val scraper = ZitaClubCalendarScraper()

    private val events: List<ScrapedEvent> by lazy {
        scraper.scrape(
            javaClass.classLoader
                .getResourceAsStream("scraper/zitaclub/zitaclub-events.ics")!!
                .bufferedReader()
                .readText()
        )
    }

    private fun event(slug: String) = events.single { it.sourceId == "zita_club:$slug" }

    @Test
    fun `scrape reads every public night and skips the private hires`() {
        events.map { it.sourceId } shouldContainExactly
            listOf(
                "zita_club:rock-at-zita-16",
                "zita_club:danse-macabre-a-tribute-to-bruno-kramm",
                "zita_club:danse-macabre-a-tribute-to-bruno-kramm-2"
            )
    }

    @Test
    fun `scrape maps a night with its Berlin times, text and poster`() {
        val event = event("rock-at-zita-16")

        event.title shouldBe "Rock at Zita"
        event.eventDate shouldBe LocalDate.of(2026, 10, 17)
        event.startTime shouldBe LocalTime.of(21, 0)
        event.endDate shouldBe LocalDate.of(2026, 10, 18)
        event.endTime shouldBe LocalTime.of(4, 0)
        event.eventType shouldBe "PARTY"
        event.sourceUrl shouldBe "https://zita-club.de/events/rock-at-zita-16/"
        event.imageUrl shouldBe "https://zita-club.de/wp-content/uploads/2026/10/Rock-at-Zita-Instagram-Post-45-594-x-840-mm-2-scaled.png"
        val description = event.description.shouldNotBeNull()
        description shouldContain "Ob du headbangen, tanzen oder einfach"
        description shouldContain "Zita Club & Bar, Am Juliusturm 64"
        description shouldNotContain "&amp;"
    }

    @Test
    fun `a night without a poster or a category is still a party`() {
        val event = event("danse-macabre-a-tribute-to-bruno-kramm-2")

        event.title shouldBe "Danse Macabre - a Tribute to Bruno Kramm"
        event.eventDate shouldBe LocalDate.of(2026, 11, 7)
        event.startTime shouldBe LocalTime.of(21, 0)
        event.eventType shouldBe "PARTY"
        event.genre.shouldBeNull()
    }

    @Test
    fun `a UTC time is moved onto the Berlin clock and an all-day date carries no time`() {
        val calendar =
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            DTSTART:20260701T190000Z
            URL:https://zita-club.de/events/summer/
            SUMMARY:Summer Night
            END:VEVENT
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20261102
            URL:https://zita-club.de/events/all-day/
            SUMMARY:Tag der offenen Tür
            END:VEVENT
            BEGIN:VEVENT
            DTSTART:20261103T190000Z
            SUMMARY:No page
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()

        val parsed = scraper.scrape(calendar)

        parsed.map { it.title } shouldContainExactly listOf("Summer Night", "Tag der offenen Tür")
        parsed[0].startTime shouldBe LocalTime.of(21, 0)
        parsed[1].eventDate shouldBe LocalDate.of(2026, 11, 2)
        parsed[1].startTime.shouldBeNull()
    }
}
