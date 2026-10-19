package de.norm.events.scraper

import de.norm.events.event.EventStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** [toEventEntity] on a show that moves house and date at once: the row it left names both, as the note states them (#3613). */
class ScrapedEventRelocatedOnTest {
    @Test
    fun `toEventEntity stores the house and the new date of a show that moved both`() {
        val badge = "VERSCHOBEN UND VERLEGT AUF DEN 13.04.27 INS HOLE 44. BEREITS GEKAUFTE TICKETS BEHALTEN IHRE GÜLTIGKEIT!"
        val prose = "Das Konzert wurde vom Lido ins Hole 44 verlegt! Das Konzert wurde vom 19.10.26 auf den 13.04.27 verschoben!"
        val lido = scrapedEvent(title = "GHOSTKID", status = "RELOCATED", statusNote = badge, description = prose, eventDate = LocalDate.of(2026, 10, 19))
        val origin = lido.toEntity()
        origin.status shouldBe "RELOCATED"
        origin.relocatedTo shouldBe "Hole 44"
        origin.relocatedOn shouldBe LocalDate.of(2027, 4, 13)
        // The same note read as a postponement badge reaches the same row.
        lido.copy(status = "POSTPONED").toEntity().relocatedOn shouldBe LocalDate.of(2027, 4, 13)
        // A house and no date: the house only.
        val houseOnly = scrapedEvent(status = "RELOCATED", statusNote = "Achtung: Die Show wird vom SO36 ins Hole44 verlegt").toEntity()
        houseOnly.relocatedTo shouldBe "Hole44"
        houseOnly.relocatedOn.shouldBeNull()
        // A new date before the old one is stored as the venue wrote it.
        val earlier = scrapedEvent(status = "RELOCATED", statusNote = "verlegt auf den 01.09.26 ins Hole 44", eventDate = LocalDate.of(2026, 10, 19))
        earlier.toEntity().relocatedOn shouldBe LocalDate.of(2026, 9, 1)
        // A show over midnight keeps the date the note names, not its end.
        val overnight =
            scrapedEvent(
                status = "RELOCATED",
                statusNote = "verlegt auf den 14.04.27 ins Hole 44",
                eventDate = LocalDate.of(2026, 10, 19),
                endDate = LocalDate.of(2026, 10, 20)
            )
        overnight.toEntity().relocatedOn shouldBe LocalDate.of(2027, 4, 14)
    }

    @Test
    fun `toEventEntity postpones a move that names a new date and no house, and schedules the new date`() {
        val note = "verlegt auf 17.08.27"
        val old = scrapedEvent(title = "Manolito Simonet y su Trabuco", status = "RELOCATED", subtitle = note, eventDate = LocalDate.of(2026, 11, 9)).toEntity()
        old.status shouldBe "POSTPONED"
        old.relocatedTo.shouldBeNull()
        old.relocatedOn.shouldBeNull()
        val new = scrapedEvent(title = "Manolito Simonet y su Trabuco", status = "RELOCATED", subtitle = note, eventDate = LocalDate.of(2027, 8, 17)).toEntity()
        new.status shouldBe "SCHEDULED"
        // No date at all: a bare move stays a bare move.
        scrapedEvent(status = "RELOCATED", statusNote = "Verlegt / Relocated").toEntity().status shouldBe "RELOCATED"
    }

    @Test
    fun `toEventEntity stores no new date on any status but RELOCATED`() {
        val note = "auf den 13.04.27 verschoben"
        EventStatus.entries.filter { it != EventStatus.RELOCATED }.forEach { status ->
            withClue(status) {
                scrapedEvent(status = status.name, statusNote = note, eventDate = LocalDate.of(2026, 10, 19)).toEntity().relocatedOn.shouldBeNull()
            }
        }
    }

    private fun scrapedEvent(
        title: String = "Berliner Weisse",
        description: String? = null,
        eventDate: LocalDate = LocalDate.of(2026, 12, 30),
        endDate: LocalDate? = null,
        status: String = "SCHEDULED",
        statusNote: String? = null,
        subtitle: String? = null
    ) = ScrapedEvent(
        title = title,
        subtitle = subtitle,
        status = status,
        statusNote = statusNote,
        eventDate = eventDate,
        endDate = endDate,
        sourceId = "so36:98223",
        sourceUrl = "https://www.so36.com/produkte/98223",
        description = description
    )

    private fun ScrapedEvent.toEntity() = toEventEntity(venueId = 1L, venueSlug = "so36", eventSourceId = 1L)
}
