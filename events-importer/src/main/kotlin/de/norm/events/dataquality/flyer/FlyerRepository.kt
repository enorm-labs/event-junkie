package de.norm.events.dataquality.flyer

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.long
import de.norm.events.common.string
import de.norm.events.event.EventType
import de.norm.events.event.PinnedField
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalTime

/** What [FlyerLineupCheck] reads of one event: the stored image's hash and the fields it may propose. */
data class FlyerTarget(
    val id: Long,
    val title: String,
    val eventDate: LocalDate,
    val startTime: LocalTime?,
    val doorsTime: LocalTime?,
    val contentHash: String
)

/**
 * The reads and writes of the flyer-lineup check. It writes `event_flyer_check` and nothing else here; the
 * suggestions go through the classification repository's dedupe (ADR-041 rule 3).
 */
@Repository
@ConditionalOnProperty(prefix = "app.data-quality", name = ["classification.enabled", "flyer.enabled"], havingValue = "true")
class FlyerRepository(
    private val template: R2dbcEntityTemplate
) {
    /**
     * Upcoming music nights of [sources] with no act, a stored image and an image not read yet, soonest first.
     *
     * A withheld image has no `image_url`, so the join leaves it out (ADR-059 rule 2). A pinned lineup is a
     * person's decision, so it is left out too.
     */
    suspend fun findCandidates(
        sources: Collection<String>,
        limit: Int
    ): List<FlyerTarget> =
        if (sources.isEmpty() || limit <= 0) {
            emptyList()
        } else {
            template.databaseClient
                .sql(
                    """
                    SELECT e.id, e.title, e.event_date, e.start_time, e.doors_time, ci.content_hash
                    FROM $EVENTS_SCHEMA.event e
                    JOIN $EVENTS_SCHEMA.event_source s ON s.id = e.event_source_id
                    JOIN $EVENTS_SCHEMA.cached_image ci ON ci.source_url = e.image_url
                    WHERE s.slug = ANY(:sources)
                      AND e.event_date >= CURRENT_DATE
                      AND e.status = 'SCHEDULED'
                      AND e.event_type IN ($MUSIC_TYPES)
                      AND NOT e.image_withheld
                      AND NOT ('$LINEUP_PIN' = ANY(e.pinned_fields))
                      AND ci.content_hash IS NOT NULL AND ci.deleted_at IS NULL
                      AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_artist ea WHERE ea.event_id = e.id)
                      AND NOT EXISTS (
                          SELECT 1 FROM $EVENTS_SCHEMA.event_flyer_check c WHERE c.event_id = e.id AND c.content_hash = ci.content_hash
                      )
                    ORDER BY e.event_date, e.id
                    LIMIT :limit
                    """.trimIndent()
                ).bind("sources", sources.toTypedArray())
                .bind("limit", limit)
                .map { row, _ ->
                    FlyerTarget(
                        id = row.long("id"),
                        title = row.string("title"),
                        eventDate = requireNotNull(row.get("event_date", LocalDate::class.java)) { "Column 'event_date' is null" },
                        startTime = row.get("start_time", LocalTime::class.java),
                        doorsTime = row.get("doors_time", LocalTime::class.java),
                        contentHash = row.string("content_hash")
                    )
                }.all()
                .asFlow()
                .toList()
        }

    /** Records that [model] read the image [contentHash] for [eventId], so the same flyer is not sent again. */
    suspend fun recordRead(
        eventId: Long,
        contentHash: String,
        model: String
    ) {
        template.databaseClient
            .sql(
                """
                INSERT INTO $EVENTS_SCHEMA.event_flyer_check (event_id, content_hash, model) VALUES (:eventId, :hash, :model)
                ON CONFLICT (event_id, content_hash) DO UPDATE SET model = EXCLUDED.model, checked_at = now()
                """.trimIndent()
            ).bind("eventId", eventId)
            .bind("hash", contentHash)
            .bind("model", model)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private companion object {
        /** The types whose flyers bill acts. A comedy or reading flyer names a performer the text check already reads. */
        val MUSIC_TYPES = listOf(EventType.PARTY, EventType.CONCERT, EventType.FESTIVAL).joinToString { "'${it.name}'" }
        val LINEUP_PIN = PinnedField.LINEUP.key
    }
}
