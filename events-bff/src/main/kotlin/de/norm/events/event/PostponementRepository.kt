package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * Finds the new date of a postponed show (#3093). A `POSTPONED` row is the date the show left
 * (ADR-030), and the venue's new date, if it names one, is another row that nothing links. The
 * match runs on each page view and stores nothing, so a wrong one goes when the data changes.
 */
@Repository
class PostponementRepository(
    private val databaseClient: DatabaseClient
) {
    /**
     * The one later `SCHEDULED` row at the same venue, within [WINDOW_MONTHS], that shares an artist
     * with [event] or has its title. Null when none matches, and when two or more do: a resident act
     * plays the venue every month, and a guess between them is worse than no link.
     */
    suspend fun newDate(event: EventEntity): PostponedToResponse? {
        if (event.status != EventStatus.POSTPONED.name) return null
        val eventId = requireNotNull(event.id) { "Persisted event must have an ID" }
        val title = comparable(event.title)
        return databaseClient
            .sql(
                """
                SELECT e.slug, e.event_date, e.title,
                       EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_artist own
                               JOIN $EVENTS_SCHEMA.event_artist other ON other.artist_id = own.artist_id
                               WHERE own.event_id = :eventId AND other.event_id = e.id) AS shares_artist
                FROM $EVENTS_SCHEMA.event e
                WHERE e.venue_id = :venueId AND e.status = 'SCHEDULED'
                  AND e.event_date > :from AND e.event_date <= :until
                ORDER BY e.event_date, e.id
                """.trimIndent()
            ).bind("eventId", eventId)
            .bind("venueId", event.venueId)
            .bind("from", event.eventDate)
            .bind("until", event.eventDate.plusMonths(WINDOW_MONTHS))
            .map { row: Readable -> row.toCandidate() }
            .all()
            .collectList()
            .awaitSingle()
            .singleOrNull { it.sharesArtist || comparable(it.title) == title }
            ?.let { PostponedToResponse(it.slug, it.eventDate) }
    }

    private class Candidate(
        val slug: String,
        val eventDate: LocalDate,
        val title: String,
        val sharesArtist: Boolean
    )

    private fun Readable.toCandidate() =
        Candidate(
            slug = requireNotNull(get("slug", String::class.java)),
            eventDate = requireNotNull(get("event_date", LocalDate::class.java)),
            title = requireNotNull(get("title", String::class.java)),
            sharesArtist = get("shares_artist", Boolean::class.javaObjectType) == true
        )

    private companion object {
        const val WINDOW_MONTHS = 24L

        /** A venue writes the status into the old row's title: `VERSCHOBEN: Kalkofes Zeitreise`. */
        val STATUS_WORD = Regex("""\b(verschoben|postponed)\b""", RegexOption.IGNORE_CASE)
        val NOT_A_LETTER_OR_DIGIT = Regex("""[^\p{L}\p{N}]""")

        fun comparable(title: String): String = title.replace(STATUS_WORD, "").replace(NOT_A_LETTER_OR_DIGIT, "").lowercase()
    }
}
