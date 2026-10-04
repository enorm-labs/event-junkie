package de.norm.events.scraper

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Repository

/** Why the sync gate kept a value out of an event. The names are the `event_quality_flag.kind` CHECK values (V100). */
enum class QualityFlagKind {
    /** An artist name with nothing a slug can keep, such as `-` (#1553). */
    SLUGLESS_ARTIST,

    /** An artist name [isNonArtistName] refuses: a placeholder, a role label, a segment. */
    NON_ARTIST_NAME,

    /** A title-derived name the event also credits as its promoter, held back unbilled (#1772). */
    HELD_BACK_PROMOTER_NAME,

    /** A genre that only repeats the event title. */
    GENRE_EQUALS_TITLE,

    /** A word in the genre field that names no genre, such as `Ausstellung`. */
    NON_GENRE_TOKEN
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
