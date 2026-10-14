package de.norm.events.dataquality.classification

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.dataquality.QualityIssue
import de.norm.events.importing.QualityFlagKind
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalTime

/** What [EventClassificationCheck] reads of one event, and the hash of its title, subtitle and description. */
data class ClassificationTarget(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val eventType: String,
    val startTime: LocalTime?,
    val pricePresale: BigDecimal?,
    val priceBoxOffice: BigDecimal?,
    val contentHash: String,
    /** The hash the last answered check saw, or null when the event was never checked. */
    val checkedHash: String?
)

/** One act of the lineup, in billing order. */
data class LineupEntry(
    val name: String,
    val titleDerived: Boolean
)

/** One proposal, as `event_suggestion` stores it. */
data class NewSuggestion(
    val eventId: Long,
    val field: String,
    val storedValue: String?,
    val proposedValue: String,
    val evidence: String,
    val confidence: Double,
    val model: String?
)

/**
 * The reads and writes of the classification check. Hand-written SQL, as in the rest of the module.
 *
 * It writes `event_suggestion` and `event_classification_check` and nothing else (ADR-041 rule 3).
 */
@Repository
@ConditionalOnProperty(name = ["app.data-quality.classification.enabled"], havingValue = "true")
class EventClassificationRepository(
    private val template: R2dbcEntityTemplate
) {
    /** The upcoming candidates of one source, soonest first. */
    suspend fun findSourceCandidateIds(sourceSlug: String): List<Long> =
        template.databaseClient
            .sql(
                """
                SELECT e.id FROM $EVENTS_SCHEMA.event e
                JOIN $EVENTS_SCHEMA.event_source s ON s.id = e.event_source_id
                WHERE s.slug = :slug AND e.event_date >= CURRENT_DATE AND ($CANDIDATE_PREDICATE)
                ORDER BY e.event_date, e.id
                """.trimIndent()
            ).bind("slug", sourceSlug)
            .map { row, _ -> row.long("id") }
            .all()
            .asFlow()
            .toList()

    suspend fun findTarget(eventId: Long): ClassificationTarget? =
        template.databaseClient
            .sql(
                """
                SELECT e.id, e.title, e.subtitle, e.description, e.event_type, e.start_time, e.price_presale, e.price_box_office,
                       $CONTENT_HASH AS content_hash, c.content_hash AS checked_hash
                FROM $EVENTS_SCHEMA.event e
                LEFT JOIN $EVENTS_SCHEMA.event_classification_check c ON c.event_id = e.id
                WHERE e.id = :id
                """.trimIndent()
            ).bind("id", eventId)
            .map { row, _ ->
                ClassificationTarget(
                    id = row.long("id"),
                    title = row.string("title"),
                    subtitle = row.get("subtitle", String::class.java),
                    description = row.get("description", String::class.java),
                    eventType = row.string("event_type"),
                    startTime = row.get("start_time", LocalTime::class.java),
                    pricePresale = row.get("price_presale", BigDecimal::class.java),
                    priceBoxOffice = row.get("price_box_office", BigDecimal::class.java),
                    contentHash = row.string("content_hash"),
                    checkedHash = row.get("checked_hash", String::class.java)
                )
            }.one()
            .awaitSingleOrNull()

    suspend fun findLineup(eventId: Long): List<LineupEntry> =
        template.databaseClient
            .sql(
                "SELECT a.name, ea.title_derived FROM $EVENTS_SCHEMA.event_artist ea " +
                    "JOIN $EVENTS_SCHEMA.artist a ON a.id = ea.artist_id WHERE ea.event_id = :id ORDER BY ea.billing_order, ea.id"
            ).bind("id", eventId)
            .map { row, _ -> LineupEntry(row.string("name"), row.get("title_derived", Boolean::class.javaObjectType) == true) }
            .all()
            .asFlow()
            .toList()

    /** The stored artists among [names] that MusicBrainz matched `EXACT` (ADR-031), by their stored spelling. */
    suspend fun findExactArtists(names: Collection<String>): List<String> =
        if (names.isEmpty()) {
            emptyList()
        } else {
            template.databaseClient
                .sql(
                    "SELECT name FROM $EVENTS_SCHEMA.artist " +
                        "WHERE musicbrainz_match = 'EXACT' AND lower(name) = ANY(:names) ORDER BY name"
                ).bind("names", names.map { it.lowercase() }.toTypedArray())
                .map { row, _ -> row.string("name") }
                .all()
                .asFlow()
                .toList()
        }

    /** Inserts [suggestion] unless an OPEN one has the same event, field and value. Returns whether it inserted. */
    suspend fun insertUnlessOpen(suggestion: NewSuggestion): Boolean =
        template.databaseClient
            .sql(
                """
                INSERT INTO $EVENTS_SCHEMA.event_suggestion
                    (event_id, field, stored_value, proposed_value, evidence, confidence, check_name, model)
                SELECT :eventId, :field, :storedValue, :proposedValue, :evidence, :confidence, :checkName, :model
                WHERE NOT EXISTS (
                    SELECT 1 FROM $EVENTS_SCHEMA.event_suggestion
                    WHERE event_id = :eventId AND field = :field AND proposed_value = :proposedValue AND status = 'OPEN'
                )
                """.trimIndent()
            ).bind("eventId", suggestion.eventId)
            .bind("field", suggestion.field)
            .bindNullable("storedValue", suggestion.storedValue)
            .bind("proposedValue", suggestion.proposedValue)
            .bind("evidence", suggestion.evidence)
            .bind("confidence", suggestion.confidence)
            .bind("checkName", EventClassificationCheck.CHECK_NAME)
            .bindNullable("model", suggestion.model)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    /** Records that [model] answered for [contentHash], so the same text is not sent again. */
    suspend fun recordChecked(
        eventId: Long,
        contentHash: String,
        model: String
    ) {
        template.databaseClient
            .sql(
                """
                INSERT INTO $EVENTS_SCHEMA.event_classification_check (event_id, content_hash, model)
                VALUES (:eventId, :hash, :model)
                ON CONFLICT (event_id) DO UPDATE SET content_hash = EXCLUDED.content_hash, model = EXCLUDED.model, checked_at = now()
                """.trimIndent()
            ).bind("eventId", eventId)
            .bind("hash", contentHash)
            .bind("model", model)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: String?
    ) = if (value == null) bindNull(name, String::class.java) else bind(name, value)

    private fun Readable.long(column: String): Long = requireNotNull(get(column, Number::class.java)) { "Column '$column' is null" }.toLong()

    private fun Readable.string(column: String): String = requireNotNull(get(column, String::class.java)) { "Column '$column' is null" }

    companion object {
        /**
         * The candidates, from [QualityIssue]'s predicates so the worklist and the check agree. A missing
         * time or price counts only beside a description, the one place the model can find it.
         */
        val CANDIDATE_PREDICATE =
            listOf(
                QualityIssue.EVENTS_TYPED_OTHER.predicate,
                QualityIssue.TITLE_DERIVED_UNMATCHED.predicate,
                QualityIssue.TITLE_DERIVED_SINGLETONS.predicate,
                "e.description IS NOT NULL AND e.description <> '' AND " +
                    "((${QualityIssue.MISSING_START_TIME.predicate}) OR (${QualityIssue.MISSING_PRICE.predicate}))",
                "EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_quality_flag f " +
                    "WHERE f.event_id = e.id AND f.kind = '${QualityFlagKind.UNCERTAIN_PARTY_FEATURE.name}')"
            ).joinToString(" OR ") { "($it)" }

        /** SHA-256 of the title, subtitle and description, joined by a unit separator. Computed in SQL only, so one definition holds. */
        private const val CONTENT_HASH =
            "encode(sha256(convert_to(concat_ws(chr(31), e.title, coalesce(e.subtitle, ''), coalesce(e.description, '')), 'UTF8')), 'hex')"
    }
}
