package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.math.BigDecimal
import java.time.LocalDate

/** `GET /events/{slug}`: a relocated event names the house it moved to, and links that house's night when it lists one. */
class EventMovedToTest : BaseControllerTest() {
    private val day: LocalDate = LocalDate.now(BERLIN).plusDays(5)
    private val later: LocalDate = day.plusMonths(10)

    @Test
    fun `links the same night at another venue that bills the same act`(): Unit =
        runBlocking {
            val columbiahalle = insertVenue("Columbiahalle", "columbiahalle")
            val huxleys =
                insertVenue(
                    "Huxleys Neue Welt",
                    "huxleys-neue-welt",
                    address = "Hasenheide 107-113",
                    latitude = BigDecimal("52.4862"),
                    longitude = BigDecimal("13.4206")
                )
            val act = insertArtist("Gentleman", "gentleman")
            linkArtist(relocated(columbiahalle, "Gentleman", "old", note = "huxleys"), act)
            linkArtist(insertEvent(huxleys, "GENTLEMAN – Gratitude Tour", "new", day), act)

            expectMovedTo("old", "Huxleys Neue Welt", "huxleys-neue-welt", "new")
            webTestClient
                .get()
                .uri("/events/old")
                .exchange()
                .expectBody()
                .jsonPath("$.movedTo.address")
                .isEqualTo("Hasenheide 107-113")
                .jsonPath("$.movedTo.city")
                .isEqualTo("Berlin")
                .jsonPath("$.movedTo.latitude")
                .isEqualTo(52.4862)
                .jsonPath("$.movedTo.longitude")
                .isEqualTo(13.4206)
        }

    @Test
    fun `carries the house on the list too, where the calendar feed and the map read it`(): Unit =
        runBlocking {
            val columbiahalle = insertVenue("Columbiahalle", "columbiahalle")
            insertVenue("Huxleys Neue Welt", "huxleys-neue-welt", address = "Hasenheide 107-113")
            relocated(columbiahalle, "Gentleman", "old", note = "huxleys")

            webTestClient
                .get()
                .uri("/events?from=$day&to=$day")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].movedTo.venueSlug")
                .isEqualTo("huxleys-neue-welt")
                .jsonPath("$.content[0].movedTo.address")
                .isEqualTo("Hasenheide 107-113")
        }

    @Test
    fun `links by title after the status word, when the note names no house`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val columbia = insertVenue("Columbia Theater", "columbia-theater")
            relocated(lido, "SONS OF LEGION - HOCHVERLEGT", "old", note = null)
            insertEvent(columbia, "Sons of Legion", "new", day)

            expectMovedTo("old", "Columbia Theater", "columbia-theater", "new")
        }

    @Test
    fun `prefers the night at the named house when two nights match`(): Unit =
        runBlocking {
            val hole = insertVenue("Hole44", "hole-44")
            val metropol = insertVenue("Metropol", "metropol")
            val mikropol = insertVenue("Mikropol", "mikropol")
            relocated(hole, "Genesis Owusu", "old", note = "Metropol")
            insertEvent(metropol, "Genesis Owusu", "at-metropol", day)
            insertEvent(mikropol, "Genesis Owusu", "at-mikropol", day)

            expectMovedTo("old", "Metropol", "metropol", "at-metropol")
        }

    @Test
    fun `links a night at the named house whose title only contains the other`(): Unit =
        runBlocking {
            val hole = insertVenue("Hole44", "hole-44")
            val metropol = insertVenue("Metropol", "metropol")
            relocated(hole, "Luke Combs UK Tribute", "old", note = "Metropol")
            insertEvent(metropol, "Luke Combs UK", "new", day)

            expectMovedTo("old", "Metropol", "metropol", "new")
        }

    @Test
    fun `names the venue by the start of its name when that house lists no night`(): Unit =
        runBlocking {
            val huxleys = insertVenue("Huxleys Neue Welt", "huxleys-neue-welt")
            insertVenue("Hole44", "hole-44")
            relocated(huxleys, "Zimmer90", "old", note = "Hole")

            expectMovedTo("old", "Hole44", "hole-44", eventSlug = null)
        }

    @Test
    fun `links the night at the named house on the date the note moved the show to`(): Unit =
        runBlocking {
            val huxleys = insertVenue("Huxleys Neue Welt", "huxleys-neue-welt")
            val hole = insertVenue("Hole44", "hole-44")
            relocated(huxleys, "CKay", "old", note = "Hole44", relocatedOn = later)
            insertEvent(hole, "CKay", "new", later)

            expectMovedTo("old", "Hole44", "hole-44", "new")
        }

    @Test
    fun `links only the same night when the note names no date`(): Unit =
        runBlocking {
            val huxleys = insertVenue("Huxleys Neue Welt", "huxleys-neue-welt")
            val hole = insertVenue("Hole44", "hole-44")
            relocated(huxleys, "CKay", "old", note = "Hole44")
            insertEvent(hole, "CKay", "new", later)

            expectMovedTo("old", "Hole44", "hole-44", eventSlug = null)
        }

    @Test
    fun `names the house alone when it lists nothing on the date the note moved the show to`(): Unit =
        runBlocking {
            val huxleys = insertVenue("Huxleys Neue Welt", "huxleys-neue-welt")
            val hole = insertVenue("Hole44", "hole-44")
            relocated(huxleys, "CKay", "old", note = "Hole44", relocatedOn = later)
            insertEvent(hole, "CKay", "same-night", day)

            expectMovedTo("old", "Hole44", "hole-44", eventSlug = null)
        }

    @Test
    fun `names nothing for an unknown house, an ambiguous name or a night that does not match`(): Unit =
        runBlocking {
            val heimathafen = insertVenue("Heimathafen Neukölln", "heimathafen-neukolln")
            insertVenue("Columbiahalle", "columbiahalle")
            insertVenue("Columbia Theater", "columbia-theater")
            val other = insertVenue("Other", "other")
            relocated(heimathafen, "Stefanie Heinzmann", "unknown", note = "rbb Sendesaal")
            relocated(heimathafen, "Someone", "ambiguous", note = "Columbia")
            insertEvent(other, "Somebody Else", "unrelated", day)

            expectNoMovedTo("unknown")
            expectNoMovedTo("ambiguous")
        }

    @Test
    fun `names nothing for a night that is not relocated`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val metropol = insertVenue("Metropol", "metropol")
            insertEvent(lido, "Loreen", "old", day)
            insertEvent(metropol, "Loreen", "new", day)

            expectNoMovedTo("old")
        }

    private suspend fun relocated(
        venueId: Long,
        title: String,
        slug: String,
        note: String?,
        relocatedOn: LocalDate? = null
    ): Long =
        insertEvent(venueId, title, slug, day).also {
            databaseClient
                .sql("UPDATE events.event SET status = 'RELOCATED', relocated_to = :note, relocated_on = :on WHERE id = :id")
                .bind("id", it)
                .let { spec -> if (note == null) spec.bindNull("note", String::class.java) else spec.bind("note", note) }
                .let { spec -> if (relocatedOn == null) spec.bindNull("on", LocalDate::class.java) else spec.bind("on", relocatedOn) }
                .await()
        }

    private fun expectMovedTo(
        slug: String,
        venueName: String,
        venueSlug: String,
        eventSlug: String?
    ) {
        val body =
            webTestClient
                .get()
                .uri("/events/$slug")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.movedTo.venueName")
                .isEqualTo(venueName)
                .jsonPath("$.movedTo.venueSlug")
                .isEqualTo(venueSlug)
        if (eventSlug == null) body.jsonPath("$.movedTo.eventSlug").doesNotExist() else body.jsonPath("$.movedTo.eventSlug").isEqualTo(eventSlug)
    }

    private fun expectNoMovedTo(slug: String) {
        webTestClient
            .get()
            .uri("/events/$slug")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.movedTo")
            .doesNotExist()
    }
}
