package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/** `GET /events/{slug}`: a postponed event names its new date when exactly one row matches, or one on the date its note named (#3093, #4334). */
class EventPostponedToTest : BaseControllerTest() {
    private val day: LocalDate = LocalDate.now(BERLIN).plusDays(5)

    @Test
    fun `links the later scheduled night at the same venue that bills the same act`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val act = insertArtist("Alex Mofa Gang", "alex-mofa-gang")
            linkArtist(postponed(lido, "ALEX MOFA GANG", "old"), act)
            linkArtist(insertEvent(lido, "Alex Mofa Gang – Tour", "new", day.plusMonths(7)), act)

            expectPostponedTo("old", "new", day.plusMonths(7))
        }

    @Test
    fun `links by title when no act is billed, after the status word in the old title`(): Unit =
        runBlocking {
            val colosseum = insertVenue("Colosseum", "colosseum")
            postponed(colosseum, "VERSCHOBEN: Kalkofes Zeitreise", "old")
            insertEvent(colosseum, "Kalkofes Zeitreise", "new", day.plusDays(40))

            expectPostponedTo("old", "new", day.plusDays(40))
        }

    @Test
    fun `links nothing when two nights match, as with a resident act`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val act = insertArtist("Resident", "resident")
            linkArtist(postponed(lido, "Resident", "old"), act)
            linkArtist(insertEvent(lido, "Resident", "first", day.plusDays(30)), act)
            linkArtist(insertEvent(lido, "Resident", "second", day.plusDays(60)), act)

            expectNoPostponedTo("old")
        }

    @Test
    fun `links nothing at another venue, on an earlier date, beyond two years or to a night that is not scheduled`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val festsaal = insertVenue("Festsaal", "festsaal")
            postponed(lido, "Holly Humberstone", "old")
            insertEvent(festsaal, "Holly Humberstone", "elsewhere", day.plusDays(30))
            insertEvent(lido, "Holly Humberstone", "earlier", day.minusDays(30))
            insertEvent(lido, "Holly Humberstone", "too-late", day.plusMonths(25))
            setStatus(insertEvent(lido, "Holly Humberstone", "cancelled", day.plusDays(60)), "CANCELLED")

            expectNoPostponedTo("old")
        }

    @Test
    fun `links the night on the date the note named when two nights match (#4334)`(): Unit =
        runBlocking {
            val wuehlmaeuse = insertVenue("Die Wühlmäuse", "die-wuhlmause")
            val old = postponed(wuehlmaeuse, "Alle Kassen, auch privat", "old")
            insertEvent(wuehlmaeuse, "Alle Kassen, auch privat", "december", day.plusDays(49))
            insertEvent(wuehlmaeuse, "Alle Kassen, auch privat", "april", day.plusMonths(6))
            setRelocatedOn(old, day.plusMonths(6))

            expectPostponedTo("old", "april", day.plusMonths(6))
        }

    @Test
    fun `links the first show of the night the note named when that night has two (#4334)`(): Unit =
        runBlocking {
            val wuehlmaeuse = insertVenue("Die Wühlmäuse", "die-wuhlmause")
            val old = postponed(wuehlmaeuse, "Michael Hatzius", "old")
            insertEvent(wuehlmaeuse, "Michael Hatzius", "matinee", day.plusYears(1))
            insertEvent(wuehlmaeuse, "Michael Hatzius", "evening", day.plusYears(1))
            insertEvent(wuehlmaeuse, "Michael Hatzius", "next-night", day.plusYears(1).plusDays(1))
            setRelocatedOn(old, day.plusYears(1))

            expectPostponedTo("old", "matinee", day.plusYears(1))
        }

    @Test
    fun `links nothing and keeps the date the note named when no night matches on it (#4334)`(): Unit =
        runBlocking {
            val ufa = insertVenue("ufaFabrik", "ufafabrik")
            val old = postponed(ufa, "Sebastian Krumbiegel", "old")
            // The one same-title night is on another date: the note's date wins over the guess.
            insertEvent(ufa, "Sebastian Krumbiegel", "other", day.plusDays(20))
            setRelocatedOn(old, day.plusMonths(5))

            expectNoPostponedTo("old")
            webTestClient
                .get()
                .uri("/events/old")
                .exchange()
                .expectBody()
                .jsonPath("$.relocatedOn")
                .isEqualTo(day.plusMonths(5).toString())
        }

    @Test
    fun `links nothing from a scheduled night`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            insertEvent(lido, "Loreen", "old", day)
            insertEvent(lido, "Loreen", "new", day.plusMonths(7))

            expectNoPostponedTo("old")
        }

    private suspend fun postponed(
        venueId: Long,
        title: String,
        slug: String
    ): Long = insertEvent(venueId, title, slug, day).also { setStatus(it, "POSTPONED") }

    private suspend fun setStatus(
        eventId: Long,
        status: String
    ) {
        databaseClient
            .sql("UPDATE events.event SET status = :status WHERE id = :id")
            .bind("status", status)
            .bind("id", eventId)
            .await()
    }

    private suspend fun setRelocatedOn(
        eventId: Long,
        date: LocalDate
    ) {
        databaseClient
            .sql("UPDATE events.event SET relocated_on = :date WHERE id = :id")
            .bind("date", date)
            .bind("id", eventId)
            .await()
    }

    private fun expectPostponedTo(
        slug: String,
        newSlug: String,
        newDate: LocalDate
    ) {
        webTestClient
            .get()
            .uri("/events/$slug")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.postponedTo.slug")
            .isEqualTo(newSlug)
            .jsonPath("$.postponedTo.eventDate")
            .isEqualTo(newDate.toString())
    }

    private fun expectNoPostponedTo(slug: String) {
        webTestClient
            .get()
            .uri("/events/$slug")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.postponedTo")
            .doesNotExist()
    }
}
