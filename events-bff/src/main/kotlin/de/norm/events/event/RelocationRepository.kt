package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/**
 * Finds where a relocated show went. A `RELOCATED` row stays at the venue it left, and the new house
 * usually lists the same night as a row of its own that nothing links. The note names the house as
 * free text ("huxleys", "Hole", "Bi Nuu"), so that text resolves to a venue only when it names one
 * unambiguously. The match runs on each page view and stores nothing, as [PostponementRepository].
 */
@Repository
class RelocationRepository(
    private val databaseClient: DatabaseClient
) {
    /**
     * The one `SCHEDULED` row at another venue that shares an act with [event] or has its title, else
     * the venue its note names. The row is on the date the note moved the show to (`relocated_on`,
     * ADR-056), else on the same night. Among several matching rows, the one at the
     * named venue wins; a row there whose title only contains the other links too. Null for every
     * other status, and when neither a row nor a venue is certain.
     */
    suspend fun newPlace(event: EventEntity): MovedToResponse? {
        if (event.status != EventStatus.RELOCATED.name) return null
        val named = namedVenue(event.relocatedTo)
        val title = comparable(event.title)
        val candidates = candidates(event)
        val matches = candidates.filter { it.sharesArtist || comparable(it.title) == title }
        val atNamed = named?.let { venue -> candidates.filter { it.place.slug == venue.slug } }.orEmpty()
        val linked =
            matches.singleOrNull()
                ?: matches.singleOrNull { it in atNamed }
                ?: atNamed.singleOrNull { overlaps(comparable(it.title), title) }
        return linked?.let { it.place.movedTo(eventSlug = it.slug) } ?: named?.movedTo(eventSlug = null)
    }

    private suspend fun candidates(event: EventEntity): List<Candidate> {
        val eventId = event.requiredId()
        return databaseClient
            .sql(
                """
                SELECT e.slug, e.title, $PLACE_COLUMNS,
                       EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_artist own
                               JOIN $EVENTS_SCHEMA.event_artist other ON other.artist_id = own.artist_id
                               WHERE own.event_id = :eventId AND other.event_id = e.id) AS shares_artist
                FROM $EVENTS_SCHEMA.event e
                JOIN $EVENTS_SCHEMA.venue v ON v.id = e.venue_id
                WHERE e.event_date = :date AND e.venue_id <> :venueId AND e.status = 'SCHEDULED'
                ORDER BY e.id
                """.trimIndent()
            ).bind("eventId", eventId)
            .bind("date", event.relocatedOn ?: event.eventDate)
            .bind("venueId", event.venueId)
            .map { row: Readable -> row.toCandidate() }
            .all()
            .collectList()
            .awaitSingle()
    }

    /**
     * The venue [note] names: an exact name or slug, else the one venue where the shorter of the two
     * starts the longer, at [MIN_PREFIX_LENGTH] letters or more (ADR-030). `Hole` is `hole-44`.
     */
    private suspend fun namedVenue(note: String?): Place? {
        val key = note?.let(::comparable)?.takeIf { it.isNotEmpty() } ?: return null
        val venues =
            databaseClient
                .sql("SELECT $PLACE_COLUMNS FROM $EVENTS_SCHEMA.venue v ORDER BY v.id")
                .map { row: Readable -> row.toPlace() }
                .all()
                .collectList()
                .awaitSingle()
        val exact = venues.filter { comparable(it.name) == key || comparable(it.slug) == key }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        return venues.singleOrNull { prefixed(comparable(it.name), key) || prefixed(comparable(it.slug), key) }
    }

    /** A venue as the move needs it: what to call it and how to get there. */
    private data class Place(
        val name: String,
        val slug: String,
        val address: String?,
        val city: String?,
        val latitude: BigDecimal?,
        val longitude: BigDecimal?
    ) {
        fun movedTo(eventSlug: String?) = MovedToResponse(name, slug, eventSlug, address, city, latitude, longitude)
    }

    private data class Candidate(
        val slug: String,
        val title: String,
        val place: Place,
        val sharesArtist: Boolean
    )

    private fun Readable.toPlace() =
        Place(
            name = requireNotNull(get("venue_name", String::class.java)),
            slug = requireNotNull(get("venue_slug", String::class.java)),
            address = get("venue_address", String::class.java),
            city = get("venue_city", String::class.java),
            latitude = get("venue_latitude", BigDecimal::class.java),
            longitude = get("venue_longitude", BigDecimal::class.java)
        )

    private fun Readable.toCandidate() =
        Candidate(
            slug = requireNotNull(get("slug", String::class.java)),
            title = requireNotNull(get("title", String::class.java)),
            place = toPlace(),
            sharesArtist = get("shares_artist", Boolean::class.javaObjectType) == true
        )

    private companion object {
        const val MIN_PREFIX_LENGTH = 4
        const val PLACE_COLUMNS =
            "v.name AS venue_name, v.slug AS venue_slug, v.address AS venue_address, v.city AS venue_city, " +
                "v.latitude AS venue_latitude, v.longitude AS venue_longitude"

        /** A venue writes the move into the title: `Kontrollverlust - HOCHVERLEGT`. */
        val STATUS_WORD = Regex("""\b(hoch|runter)?verlegt\b|\b(verschoben|moved|relocated)\b""", RegexOption.IGNORE_CASE)
        val NOT_A_LETTER_OR_DIGIT = Regex("""[^\p{L}\p{N}]""")

        fun comparable(text: String): String = text.replace(STATUS_WORD, "").replace(NOT_A_LETTER_OR_DIGIT, "").lowercase()

        fun prefixed(
            a: String,
            b: String
        ): Boolean = minOf(a.length, b.length) >= MIN_PREFIX_LENGTH && (a.startsWith(b) || b.startsWith(a))

        /** "Luke Combs UK Tribute" at the old house, "Luke Combs UK" at the new one. */
        fun overlaps(
            a: String,
            b: String
        ): Boolean = minOf(a.length, b.length) >= MIN_PREFIX_LENGTH && (a.contains(b) || b.contains(a))
    }
}
