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
        old.relocatedOn shouldBe LocalDate.of(2027, 8, 17)
        val new = scrapedEvent(title = "Manolito Simonet y su Trabuco", status = "RELOCATED", subtitle = note, eventDate = LocalDate.of(2027, 8, 17)).toEntity()
        new.status shouldBe "SCHEDULED"
        // No date at all: a bare move stays a bare move.
        scrapedEvent(status = "RELOCATED", statusNote = "Verlegt / Relocated").toEntity().status shouldBe "RELOCATED"
    }

    @Test
    fun `toEventEntity postpones a cancelled show whose note names a later date, and keeps the new date's row scheduled`() {
        // Wühlmäuse: the programme line carries the move.
        val programme = "Solo-Theater-Comedy mit Nebenwirkungen - VERLEGT auf den 24.04.2027"
        val left = scrapedEvent(status = "CANCELLED", subtitle = programme, eventDate = LocalDate.of(2026, 10, 31)).toEntity()
        left.status shouldBe "POSTPONED"
        left.relocatedTo.shouldBeNull()
        scrapedEvent(status = "SCHEDULED", subtitle = programme, eventDate = LocalDate.of(2027, 4, 24)).toEntity().status shouldBe "SCHEDULED"
        // Tempodrom: JSON-LD says cancelled, the subtitle says moved.
        val subtitle = "Verschoben auf den 31.05.2027 | Rocking & Symphonic"
        scrapedEvent(status = "CANCELLED", subtitle = subtitle, eventDate = LocalDate.of(2026, 11, 17)).toEntity().status shouldBe "POSTPONED"
        // A note naming a date before the row's own is no move ahead.
        val earlier = scrapedEvent(status = "CANCELLED", subtitle = "verlegt auf den 01.09.2026", eventDate = LocalDate.of(2026, 10, 31))
        earlier.toEntity().status shouldBe "CANCELLED"
        // A new date and another house: the row it left is a relocation.
        val moved = scrapedEvent(status = "CANCELLED", statusNote = "verlegt auf den 18.03.27 im Säälchen", eventDate = LocalDate.of(2026, 10, 6)).toEntity()
        moved.status shouldBe "RELOCATED"
        moved.relocatedTo shouldBe "Säälchen"
    }

    @Test
    fun `toEventEntity relocates a postponed show whose note names another house, with the new date where it names one`() {
        // Kantine am Berghain: the date and the house in two clauses of one sentence.
        val kantine = "Das Konzert wurde vom 29.10.26 auf den 13.03.27 verschoben und aus der Kantine ins Badehaus verlegt!"
        val left = scrapedEvent(title = "Yosho", status = "POSTPONED", description = kantine, eventDate = LocalDate.of(2026, 10, 29))
        left.toEntity("kantine-am-berghain").also {
            it.status shouldBe "RELOCATED"
            it.relocatedTo shouldBe "Badehaus"
            it.relocatedOn shouldBe LocalDate.of(2027, 3, 13)
        }
        // The new date's row at the other house names its own date, and takes place.
        left.copy(eventDate = LocalDate.of(2027, 3, 13)).toEntity("badehaus").status shouldBe "SCHEDULED"
        // UFO im Velodrom: a rescheduled badge, a house and no date, on a show over midnight with a long title.
        val ufo =
            scrapedEvent(
                title = "Tomora — " + "A Very Long Tour Name ".repeat(12).trim(),
                status = "POSTPONED",
                description = "Die Show wird vom UFO in die Columbiahalle verlegt.",
                eventDate = LocalDate.of(2026, 11, 1),
                endDate = LocalDate.of(2026, 11, 2)
            )
        ufo.toEntity("ufo-im-velodrom").also {
            it.status shouldBe "RELOCATED"
            it.relocatedTo shouldBe "Columbiahalle"
            it.relocatedOn.shouldBeNull()
        }
        ufo.toEntity("columbiahalle").status shouldBe "POSTPONED"
        // Max-Schmeling-Halle names only itself, as the house the show left.
        val msh = scrapedEvent(status = "POSTPONED", description = "Achtung, die Show wurde aus der Max-Schmeling-Halle verlegt.")
        msh.toEntity("max-schmeling-halle").also {
            it.status shouldBe "RELOCATED"
            it.relocatedTo.shouldBeNull()
        }
    }

    @Test
    fun `toEventEntity stores the new date on every status that stores a move, and on no other`() {
        val note = "auf den 13.04.27 verschoben"
        EventStatus.entries.forEach { status ->
            val entity = scrapedEvent(status = status.name, statusNote = note, eventDate = LocalDate.of(2026, 10, 19)).toEntity()
            withClue("$status stored as ${entity.status}") {
                if (entity.status in setOf("RELOCATED", "POSTPONED")) {
                    entity.relocatedOn shouldBe LocalDate.of(2027, 4, 13)
                } else {
                    entity.relocatedOn.shouldBeNull()
                }
            }
        }
        // The row on the new date takes place, and stores no date.
        scrapedEvent(status = "POSTPONED", statusNote = note, eventDate = LocalDate.of(2027, 4, 13)).toEntity().also {
            it.status shouldBe "SCHEDULED"
            it.relocatedOn.shouldBeNull()
        }
    }

    @Test
    fun `toEventEntity stores the new date of a postponed show as its note names it (#4334)`() {
        // Die Wühlmäuse: the programme line and the prose both name the date.
        val kassen =
            scrapedEvent(
                title = "Alle Kassen, auch privat",
                status = "POSTPONED",
                subtitle = "Solo-Theater-Comedy mit Nebenwirkungen – VERLEGT auf den 24.04.2027",
                description = "Leider müssen wir mitteilen, dass die Veranstaltung krankheitsbedingt verlegt werden muss auf den 24.04.2027.",
                eventDate = LocalDate.of(2026, 10, 31)
            ).toEntity("die-wuhlmause")
        kassen.status shouldBe "POSTPONED"
        kassen.relocatedOn shouldBe LocalDate.of(2027, 4, 24)
        // ufaFabrik: one sentence, nothing else.
        val krumbiegel =
            scrapedEvent(
                title = "Sebastian Krumbiegel",
                status = "POSTPONED",
                description = "Verlegt auf den 02.04.2027.",
                eventDate = LocalDate.of(2026, 11, 20)
            )
        krumbiegel.toEntity("ufafabrik").relocatedOn shouldBe LocalDate.of(2027, 4, 2)
        // A long title and a show over midnight change nothing.
        krumbiegel
            .copy(title = "Sebastian Krumbiegel – " + "Die Demokratie ist weiblich ".repeat(10).trim(), endDate = LocalDate.of(2026, 11, 21))
            .toEntity("ufafabrik")
            .relocatedOn shouldBe LocalDate.of(2027, 4, 2)
    }

    @Test
    fun `toEventEntity gives each night of a postponed run the new date its note pairs with it (#4334)`() {
        // Die Wühlmäuse: a range in the programme line, one sentence per night in the prose.
        val subtitle = "echsklusiv – VERLEGT auf 04.-07.11.2027 (!), 20 Uhr"
        val prose =
            "Leider müssen die Veranstaltungen in der Zeit vom 05.-08.11.2026 krankheitsbedingt verschoben werden. " +
                "Der 05.11.2026, 20 Uhr verschiebt sich auf den 04.11.2027, " +
                "Der 06.11.2026, 20 Uhr verschiebt sich auf den 05.11.2027, Der 07.11.2026, 20 Uhr verschiebt sich auf den 06.11.2027, " +
                "Der 08.11.2026, 20 Uhr verschiebt sich auf den 07.11.2027. Die Karten behalten ihre Gültigkeit."
        val night = { day: Int ->
            scrapedEvent(title = "Michael Hatzius", status = "POSTPONED", subtitle = subtitle, description = prose, eventDate = LocalDate.of(2026, 11, day))
        }
        (5..8).forEach { day ->
            withClue("2026-11-0$day") { night(day).toEntity("die-wuhlmause").relocatedOn shouldBe LocalDate.of(2027, 11, day - 1) }
        }
        // A night the prose does not name takes the first new date after it.
        night(4).toEntity("die-wuhlmause").relocatedOn shouldBe LocalDate.of(2027, 11, 4)
        // The range alone names no date.
        scrapedEvent(status = "POSTPONED", subtitle = subtitle, eventDate = LocalDate.of(2026, 11, 5)).toEntity().relocatedOn.shouldBeNull()
        // A date the note names before the row's own is not a postponement's new date.
        scrapedEvent(status = "POSTPONED", statusNote = "auf den 01.09.2026", eventDate = LocalDate.of(2026, 10, 31)).toEntity().relocatedOn.shouldBeNull()
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

    private fun ScrapedEvent.toEntity(venueSlug: String = "so36") = toEventEntity(venueId = 1L, venueSlug = venueSlug, eventSourceId = 1L)
}
