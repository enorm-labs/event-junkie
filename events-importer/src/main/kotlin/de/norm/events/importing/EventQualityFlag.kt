package de.norm.events.importing

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Repository

/**
 * Why an event carries an `event_quality_flag` row. The names are the `event_quality_flag.kind` CHECK values (V100, V132).
 *
 * Most kinds record a value the sync gate kept out of the event. A [forReview] kind records a cue for
 * a person to read, and the event lost nothing, so the regression gate does not count it (#3148).
 */
enum class QualityFlagKind(
    val forReview: Boolean = false
) {
    /** An artist name with nothing a slug can keep, such as `-` (#1553). */
    SLUGLESS_ARTIST,

    /** An artist name [isNonArtistName] refuses: a placeholder, a role label, a segment. */
    NON_ARTIST_NAME,

    /** A title-derived name the event also credits as its promoter, held back unbilled (#1772). */
    HELD_BACK_PROMOTER_NAME,

    /** A genre that only repeats the event title. */
    GENRE_EQUALS_TITLE,

    /** A word in the genre field that names no genre, such as `Ausstellung`. */
    NON_GENRE_TOKEN,

    /**
     * A cue for a party feature that no clear phrase settles, such as a bare `FLINTA*` (#2631). The
     * value is the feature's slug, a colon and the cue in its words. Recorded by [EventFeatureSync].
     */
    UNCERTAIN_PARTY_FEATURE(forReview = true);

    companion object {
        /** The [forReview] kinds as an SQL list, for a `NOT IN` in a constant query. A test holds it to the enum. */
        const val REVIEW_KINDS_SQL = "'UNCERTAIN_PARTY_FEATURE'"
    }
}

/** One value the sync gate kept out of [eventId], as the venue published it. */
data class EventQualityFlag(
    val eventId: Long,
    val kind: QualityFlagKind,
    val value: String
)

/**
 * The `event_quality_flag` rows. Hand-written SQL, because the key is all three columns and Spring
 * Data needs a single `@Id`.
 */
@Repository
class EventQualityFlagRepository(
    private val template: R2dbcEntityTemplate
) {
    /** Replaces every flag of [eventIds] with [flags], so a row always describes the latest import of its event. */
    suspend fun replaceFor(
        eventIds: Collection<Long>,
        flags: Collection<EventQualityFlag>
    ) {
        if (eventIds.isEmpty()) return
        template.databaseClient
            .sql("DELETE FROM $EVENTS_SCHEMA.event_quality_flag WHERE event_id = ANY(:ids)")
            .bind("ids", eventIds.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
        add(flags)
    }

    /**
     * Adds [flags] beside the ones the events already have. Only for a writer that runs after
     * [replaceFor] in the same import, so its rows are as fresh as the gate's.
     */
    suspend fun add(flags: Collection<EventQualityFlag>) {
        val rows = flags.distinct()
        if (rows.isEmpty()) return
        template.databaseClient
            .sql(
                "INSERT INTO $EVENTS_SCHEMA.event_quality_flag (event_id, kind, value) " +
                    "SELECT * FROM unnest(:eventIds::bigint[], :kinds::text[], :values::text[])"
            ).bind("eventIds", rows.map { it.eventId }.toTypedArray())
            .bind("kinds", rows.map { it.kind.name }.toTypedArray())
            .bind("values", rows.map { it.value }.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    /** Deletes the flags of [kind] on [eventId], and returns how many there were. */
    suspend fun deleteKind(
        eventId: Long,
        kind: QualityFlagKind
    ): Long =
        template.databaseClient
            .sql("DELETE FROM $EVENTS_SCHEMA.event_quality_flag WHERE event_id = :id AND kind = :kind")
            .bind("id", eventId)
            .bind("kind", kind.name)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull() ?: 0

    /** The flags of [eventIds], in key order. */
    suspend fun findByEventIds(eventIds: Collection<Long>): List<EventQualityFlag> =
        if (eventIds.isEmpty()) {
            emptyList()
        } else {
            template.databaseClient
                .sql(
                    "SELECT event_id, kind, value FROM $EVENTS_SCHEMA.event_quality_flag " +
                        "WHERE event_id = ANY(:ids) ORDER BY event_id, kind, value"
                ).bind("ids", eventIds.toTypedArray())
                .map { row, _ ->
                    EventQualityFlag(
                        eventId = requireNotNull(row.get("event_id", Number::class.java)).toLong(),
                        kind = QualityFlagKind.valueOf(requireNotNull(row.get("kind", String::class.java))),
                        value = requireNotNull(row.get("value", String::class.java))
                    )
                }.all()
                .asFlow()
                .toList()
        }
}
