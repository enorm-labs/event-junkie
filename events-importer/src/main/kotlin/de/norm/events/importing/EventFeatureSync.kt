package de.norm.events.importing

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.event.EventEntity
import de.norm.events.event.PartyFeature
import de.norm.events.event.PartyFeatureMatch
import de.norm.events.event.PartyFeatureRules
import de.norm.events.event.PinnedField
import de.norm.events.scraper.EventSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service

/** One `event_feature` row: a [feature] slug on [eventId] and the [matchedPhrase] of the event's text that sets it (#2631). */
data class EventFeatureRow(
    val eventId: Long,
    val feature: String,
    val matchedPhrase: String
)

/** The `event_feature` rows. Hand-written SQL, because the key is the pair `(event_id, feature)` and Spring Data needs a single `@Id`. */
@Repository
class EventFeatureRepository(
    private val template: R2dbcEntityTemplate
) {
    /** Replaces every feature of [eventIds] with [rows], so a row always describes the latest import of its event. */
    suspend fun replaceFor(
        eventIds: Collection<Long>,
        rows: Collection<EventFeatureRow>
    ) {
        if (eventIds.isEmpty()) return
        template.databaseClient
            .sql("DELETE FROM $EVENTS_SCHEMA.event_feature WHERE event_id = ANY(:ids)")
            .bind("ids", eventIds.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
        if (rows.isEmpty()) return
        template.databaseClient
            .sql(
                "INSERT INTO $EVENTS_SCHEMA.event_feature (event_id, feature, matched_phrase) " +
                    "SELECT * FROM unnest(:eventIds::bigint[], :features::text[], :phrases::text[])"
            ).bind("eventIds", rows.map { it.eventId }.toTypedArray())
            .bind("features", rows.map { it.feature }.toTypedArray())
            .bind("phrases", rows.map { it.matchedPhrase }.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    /** The features of [eventIds], in key order. */
    suspend fun findByEventIds(eventIds: Collection<Long>): List<EventFeatureRow> =
        if (eventIds.isEmpty()) {
            emptyList()
        } else {
            template.databaseClient
                .sql(
                    "SELECT event_id, feature, matched_phrase FROM $EVENTS_SCHEMA.event_feature " +
                        "WHERE event_id = ANY(:ids) ORDER BY event_id, feature"
                ).bind("ids", eventIds.toTypedArray())
                .map { row, _ ->
                    EventFeatureRow(
                        eventId = requireNotNull(row.get("event_id", Number::class.java)).toLong(),
                        feature = requireNotNull(row.get("feature", String::class.java)),
                        matchedPhrase = requireNotNull(row.get("matched_phrase", String::class.java))
                    )
                }.all()
                .asFlow()
                .toList()
        }
}

/**
 * The sources whose bare "afterhour" means the night runs on with no stated end (#3386). Elsewhere an
 * afterhour can be a short after-party, so the cue stays uncertain. A source joins only on the operator's word.
 */
val AFTERHOUR_OPEN_END_SOURCES: Set<EventSource> = setOf(EventSource.GOLDEN_GATE)

/**
 * Reads the party features of each saved event off its stored title, subtitle and description
 * ([PartyFeatureRules]) and replaces its `event_feature` rows (#2631). A cue the rules cannot settle
 * goes to the data-quality worklist as [QualityFlagKind.UNCERTAIN_PARTY_FEATURE].
 *
 * It reads the entity as saved, so a pinned title or description (ADR-042) is the text it reads, and
 * a description the licence withholds was never stored. Runs after [AssociationSyncService], whose
 * [EventQualityFlagRepository.replaceFor] has cleared the events' flags for this import.
 *
 * An event that pins [PinnedField.FEATURES] keeps the features set by hand ([EventFeatureService]),
 * and gets no uncertain cue back: the operator has decided.
 *
 * An afterhour cue from one of [AFTERHOUR_OPEN_END_SOURCES] sets [PartyFeature.OPEN_END] instead of a flag.
 */
@Service
class EventFeatureSync(
    private val featureRepository: EventFeatureRepository,
    private val qualityFlagRepository: EventQualityFlagRepository
) {
    private val logger = KotlinLogging.logger {}

    suspend fun sync(savedEvents: List<EventEntity>) {
        val (pinned, open) = savedEvents.partition { PinnedField.FEATURES.key in it.pinnedFields }
        val rows = mutableListOf<EventFeatureRow>()
        val flags = mutableListOf<EventQualityFlag>()
        open.forEach { event ->
            val id = event.id ?: return@forEach
            val detection = PartyFeatureRules.detect(event.title, event.subtitle, event.description)
            detection.features.mapTo(rows) { EventFeatureRow(id, it.feature.slug, it.phrase) }
            detection.uncertain.forEach { cue ->
                val afterhour = cue.afterhourOpenEnd(event.sourceId)
                if (afterhour != null) {
                    rows += EventFeatureRow(id, cue.feature.slug, afterhour)
                } else {
                    flags += EventQualityFlag(id, QualityFlagKind.UNCERTAIN_PARTY_FEATURE, "${cue.feature.slug}: ${cue.phrase}")
                }
            }
        }
        featureRepository.replaceFor(open.mapNotNull { it.id }, rows)
        qualityFlagRepository.add(flags)
        if (rows.isNotEmpty() || flags.isNotEmpty() || pinned.isNotEmpty()) {
            logger.info {
                "Set ${rows.size} party feature(s) ${rows.groupingBy { it.feature }.eachCount()}; " +
                    "flagged ${flags.size} uncertain cue(s) for the data-quality worklist; " +
                    "kept the pinned features of ${pinned.size} event(s)"
            }
        }
    }

    /** The afterhour word of an [PartyFeature.OPEN_END] cue from one of [AFTERHOUR_OPEN_END_SOURCES], or null for any other cue. */
    private fun PartyFeatureMatch.afterhourOpenEnd(sourceId: String): String? =
        if (feature == PartyFeature.OPEN_END && AFTERHOUR_OPEN_END_SOURCES.any { sourceId.startsWith(it.sourceIdPrefix) }) {
            PartyFeatureRules.AFTERHOUR.find(phrase)?.value
        } else {
            null
        }
}
