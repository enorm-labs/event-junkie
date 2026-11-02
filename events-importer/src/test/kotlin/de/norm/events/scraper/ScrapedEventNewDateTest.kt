package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** [toEventEntity] on a postponed show whose note names the new date in a form other than "auf den 01.10.2026". */
class ScrapedEventNewDateTest {
    @Test
    fun `toEventEntity schedules the new date's row when the note writes the date without a year, in words or as a month`() {
        fun postponed(
            note: String,
            date: LocalDate
        ) = scrapedEvent(status = "POSTPONED", statusNote = note, eventDate = date).toEntity().status

        // Columbia Theater flags the new date's page and writes no year.
        postponed("Verschoben vom 06.08. auf den 25.03. / Tickets behalten ihre Gültigkeit.", LocalDate.of(2027, 3, 25)) shouldBe "SCHEDULED"
        // Uber Eats Music Hall writes the month out.
        postponed("Das Konzert wird auf den 24. April 2027 verlegt. Tickets behalten ihre Gültigkeit.", LocalDate.of(2027, 4, 24)) shouldBe "SCHEDULED"
        // Badehaus names only the month.
        postponed("Debüttour wird in den März 2027 verschoben", LocalDate.of(2027, 3, 13)) shouldBe "SCHEDULED"
        // Modus names only the year the show left.
        postponed("Luna Simao (verschoben aus 2026)", LocalDate.of(2027, 4, 13)) shouldBe "SCHEDULED"

        // The same notes on the date the show left keep it postponed.
        postponed("Verschoben vom 06.08. auf den 25.03.", LocalDate.of(2026, 8, 6)) shouldBe "POSTPONED"
        postponed("Das Konzert wird auf den 24. April 2027 verlegt.", LocalDate.of(2026, 11, 8)) shouldBe "POSTPONED"
        postponed("Debüttour wird in den März 2027 verschoben", LocalDate.of(2026, 10, 3)) shouldBe "POSTPONED"
        postponed("Luna Simao (verschoben aus 2026)", LocalDate.of(2026, 4, 16)) shouldBe "POSTPONED"
        // A full date never matches as a yearless one: Die Wühlmäuse's run moves by a year less a day.
        postponed("VERLEGT auf den 05.11.2027", LocalDate.of(2026, 11, 5)) shouldBe "POSTPONED"
    }

    @Test
    fun `toEventEntity reads Columbiahalle auf dem as a new date`() {
        val left = scrapedEvent(status = "RELOCATED", statusNote = "auf dem 24.10.27 verlegt", eventDate = LocalDate.of(2026, 11, 26)).toEntity()
        left.status shouldBe "POSTPONED"
        left.relocatedTo.shouldBeNull()
        left.relocatedOn shouldBe LocalDate.of(2027, 10, 24)
    }

    private fun scrapedEvent(
        eventDate: LocalDate,
        status: String,
        statusNote: String
    ) = ScrapedEvent(
        title = "Berliner Weisse",
        status = status,
        statusNote = statusNote,
        eventDate = eventDate,
        sourceId = "so36:98223",
        sourceUrl = "https://www.so36.com/produkte/98223"
    )

    private fun ScrapedEvent.toEntity() = toEventEntity(venueId = 1L, venueSlug = "so36", eventSourceId = 1L)
}
