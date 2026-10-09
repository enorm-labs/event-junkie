package de.norm.events.importing

import de.norm.events.event.EventContentStamp
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.event.PinnedField
import de.norm.events.licence.SourceLicences
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.dropPastEvents
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * The import of an [SourceRole.ENRICHMENT] source (ADR-043): it fills the empty fields of the events
 * that the venue's main source lists, and nothing else. It never inserts an event and never deletes
 * one, never changes a value that is set, and never touches a field pinned by hand (ADR-042). Each
 * filled field is recorded with the page it came from ([EventEnrichmentRepository]), so the event
 * page credits that page and the main source's next import knows which values it may replace.
 * Called within a transactional boundary managed by the caller, as [EventUpsertService] is.
 */
@Service
class EventEnrichmentService(
    private val eventRepository: EventRepository,
    private val associationSyncService: AssociationSyncService,
    private val enrichmentRepository: EventEnrichmentRepository,
    private val contentStamp: EventContentStamp,
    /** Berlin, as for [EventUpsertService]: "today" decides which events are past. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Matches each of [scrapedEvents] to a main event of venue [venueId] by ADR-043 rule 5 and fills
     * that event's empty, unpinned fields from it. An event that matches none is dropped and counted,
     * as is one that fits several. Two enrichment events on one main event fill it in page order.
     *
     * @param eventSourceId the enrichment source; its own id never owns an event.
     * @param licences the enrichment source's, applied to what it fills as to any source's row.
     */
    @Suppress("LongParameterList") // The same facts about the source as EventUpsertService.upsertAndCleanup.
    suspend fun enrich(
        scrapedEvents: List<ScrapedEvent>,
        venueId: Long,
        venueSlug: String,
        eventSourceId: Long,
        licences: SourceLicences = SourceLicences.UNKNOWN_SOURCE
    ): EnrichmentOutcome {
        val upcoming = scrapedEvents.dropPastEvents(clock) { logger.info { "Dropped $it past event(s) from enrichment source $eventSourceId" } }
        val stored =
            if (upcoming.isEmpty()) {
                emptyList()
            } else {
                eventRepository
                    .findByVenueIdAndEventDateIn(venueId, upcoming.mapTo(mutableSetOf()) { it.eventDate })
                    .toList()
                    .filter { it.eventSourceId != eventSourceId }
            }
        val matching = match(upcoming, stored)

        val current = stored.associateByTo(mutableMapOf()) { requireNotNull(it.id) }
        val filledColumns = fillColumns(matching.matched, current) { it.toEventEntity(venueId, venueSlug, eventSourceId, licences = licences) }
        if (filledColumns.isNotEmpty()) eventRepository.saveAll(filledColumns.keys.map { current.getValue(it) }).toList()

        // The first enrichment event of each main event fills its join tables, as it filled its columns first.
        val firstMatches = matching.matched.distinctBy { it.first }
        val associations =
            associationSyncService.fillEmptyAssociations(
                firstMatches.map { (id, event) -> current.getValue(id).let { it to event.copy(sourceId = it.sourceId) } },
                genreFilled = filledColumns.filterValues { PinnedField.GENRE in it }.keys
            )
        contentStamp.restamp(firstMatches.map { current.getValue(it.first) })

        val pageOf = firstMatches.toMap()
        val filled = (filledColumns.keys + associations.filled.keys).associateWith { filledColumns[it].orEmpty() + associations.filled[it].orEmpty() }
        filled.forEach { (id, fields) ->
            enrichmentRepository.record(EventEnrichment(id, eventSourceId, pageOf.getValue(id).sourceUrl, fields.mapTo(mutableSetOf()) { it.key }))
        }

        val outcome =
            EnrichmentOutcome(
                matched = matching.matched.size,
                unmatched = matching.unmatched,
                ambiguous = matching.ambiguous,
                fieldsFilled = filled.values.sumOf { it.size },
                touchedArtistIds = associations.touchedArtistIds
            )
        logger.info {
            "Enrichment source $eventSourceId: ${outcome.matched} matched, ${outcome.unmatched} unmatched, ${outcome.ambiguous} ambiguous, " +
                "${outcome.fieldsFilled} field(s) filled on ${filled.size} event(s)"
        }
        return outcome
    }

    /** Each of [events] matched against [stored] by ADR-043 rule 5; a dropped one is logged. */
    private fun match(
        events: List<ScrapedEvent>,
        stored: List<EventEntity>
    ): Matching {
        val matched = mutableListOf<Pair<Long, ScrapedEvent>>()
        var unmatched = 0
        var ambiguous = 0
        for (event in events) {
            when (val match = matchEnrichment(event, stored)) {
                is EnrichmentMatch.Matched -> {
                    matched.add(requireNotNull(match.event.id) to event)
                }

                EnrichmentMatch.Unmatched -> {
                    unmatched++
                    logDropped(event, "matches no event of the venue")
                }

                EnrichmentMatch.Ambiguous -> {
                    ambiguous++
                    logDropped(event, "fits several events of the venue, and the title decides none")
                }
            }
        }
        return Matching(matched, unmatched, ambiguous)
    }

    /** [match]'s result: the matched pairs by main event id, and the two counts of dropped events. */
    private data class Matching(
        val matched: List<Pair<Long, ScrapedEvent>>,
        val unmatched: Int,
        val ambiguous: Int
    )

    /**
     * Fills the empty, unpinned columns of each matched row in [current] from the row [offer] builds
     * of its enrichment event, in place. A column the first enrichment event filled is no longer empty
     * for a second one.
     *
     * @return the columns filled, by event id.
     */
    private fun fillColumns(
        matched: List<Pair<Long, ScrapedEvent>>,
        current: MutableMap<Long, EventEntity>,
        offer: (ScrapedEvent) -> EventEntity
    ): Map<Long, Set<PinnedField>> {
        val filled = mutableMapOf<Long, MutableSet<PinnedField>>()
        for ((id, event) in matched) {
            val row = current.getValue(id)
            val offered = offer(event)
            val fields = PinnedField.ENRICHABLE.filter { it.isColumn && it.key !in row.pinnedFields && it.isEmptyOn(row) && !it.isEmptyOn(offered) }
            if (fields.isNotEmpty()) {
                current[id] = fields.fold(row) { result, field -> field.takeFrom(result, offered) }
                filled.getOrPut(id) { mutableSetOf() }.addAll(fields)
            }
        }
        return filled
    }

    /** One dropped enrichment event at INFO: the evidence ADR-043's "When to revisit" asks for. */
    private fun logDropped(
        event: ScrapedEvent,
        why: String
    ) {
        logger.at(Level.INFO) {
            message = "Dropped enrichment event '${event.title}' on ${event.eventDate}${event.startTime?.let { " $it" }.orEmpty()}: it $why"
            payload = mapOf(LogFields.EVENT_SOURCE_ID to event.sourceId)
        }
    }
}

/** What one enrichment run did (ADR-043). [EventImportService] logs it and [ImporterMetrics] counts it. */
data class EnrichmentOutcome(
    /** Enrichment events that matched one main event. */
    val matched: Int,
    /** Enrichment events that matched none: shows the venue does not list. */
    val unmatched: Int,
    /** Enrichment events that fitted several main events and were dropped. */
    val ambiguous: Int,
    /** Fields filled, columns and join tables together. */
    val fieldsFilled: Int,
    /** The artist rows a filled lineup billed, for the MusicBrainz sweep after the commit. */
    val touchedArtistIds: Set<Long> = emptySet()
)
