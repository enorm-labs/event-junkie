package de.norm.events.scraper

import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.licence.SourceLicences
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The persistence pipeline for scraped events: deduplication, upsert, stale cleanup.
 * Association management is [AssociationSyncService]'s. Called within a transactional boundary
 * managed by the caller.
 */
@Service
class EventUpsertService(
    private val eventRepository: EventRepository,
    private val associationSyncService: AssociationSyncService,
    /**
     * Injected clock; Berlin in production, because a UTC "today" dropped last night one or two
     * hours late and out of step with the BFF (#299).
     */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Deduplicates, upserts and cleans up stale events for one source, within the caller's
     * transaction: drop events dated before today ([dropPastEvents]), deduplicate by generated slug,
     * remove stale future events, upsert by `sourceId`, sync associations
     * ([AssociationSyncService]).
     *
     * Cleanup before upsert, deliberately: `event.slug` is `UNIQUE` and derived from date + venue +
     * title, not `sourceId`, so a stale row can sit on the slug an incoming row needs. SO36 listed a
     * festival combi ticket (`so36:90006`) then a day-one ticket (`so36:93090`) with the same title
     * and date; with the upsert first, the `INSERT` hit the old row's slug and the `executeMany`
     * batch failed, taking all 111 SO36 events with it. Deleting first frees the slug, inside the
     * same transaction.
     *
     * @param scrapedEvents the raw events from the scraper; may contain duplicates.
     * @return what the upsert did, split by operation. [UpsertOutcome.total] is what the source's
     * `lastEventCount` records.
     */
    suspend fun upsertAndCleanup(
        scrapedEvents: List<ScrapedEvent>,
        venueId: Long,
        venueSlug: String,
        eventSourceId: Long,
        licences: SourceLicences = SourceLicences.UNKNOWN_SOURCE
    ): UpsertOutcome {
        val upcomingEvents = dropPastEvents(scrapedEvents, eventSourceId)
        val uniqueEvents = deduplicateScrapedEvents(upcomingEvents)
        // Cleanup BEFORE the upsert; the order is load-bearing (KDoc).
        removeStaleEvents(uniqueEvents, eventSourceId)
        return upsertEvents(uniqueEvents, venueId, venueSlug, eventSourceId, licences)
            // Counted here rather than where they are dropped, because the tag needs the source slug and
            // this service holds only the numeric id (#982).
            .copy(
                droppedPast = scrapedEvents.size - upcomingEvents.size,
                droppedDuplicate = upcomingEvents.size - uniqueEvents.size
            )
    }

    /**
     * Drops scraped events dated before today. Calendar-style sources publish the whole standing
     * programme, and [removeStaleEvents] never prunes past-dated rows, so re-importing would
     * resurrect them every run. Same-day events are kept, matching the `tomorrow` lower bound used
     * for cleanup. Existing past rows are untouched, simply not re-upserted.
     *
     * @param scrapedEvents the raw events from the scraper.
     * @param eventSourceId the owning [EventSourceEntity]'s id, for logging.
     * @return the scraped events dated today or later.
     */
    private fun dropPastEvents(
        scrapedEvents: List<ScrapedEvent>,
        eventSourceId: Long
    ): List<ScrapedEvent> =
        scrapedEvents.dropPastEvents(clock) { dropped ->
            logger.info { "Dropped $dropped past event(s) from event source $eventSourceId" }
        }

    /**
     * Upserts pre-deduplicated scraped events. An event whose `sourceId` exists is saved only when
     * business-relevant fields changed, avoiding UPDATEs and inflated `updated_at`; new events are
     * inserted. Associations are [AssociationSyncService]'s.
     *
     * @return inserted / updated / skipped. Not extra work: the split was already computed for the
     * debug log, and `skipped` is the `unchanged` partition change detection produces (#415).
     */
    private suspend fun upsertEvents(
        scrapedEvents: List<ScrapedEvent>,
        venueId: Long,
        venueSlug: String,
        eventSourceId: Long,
        licences: SourceLicences
    ): UpsertOutcome {
        val existingBySourceId =
            eventRepository
                .findBySourceIdIn(scrapedEvents.map { it.sourceId })
                .toList()
                .associateBy { it.sourceId }

        val discriminators = slugDiscriminators(scrapedEvents)
        val entities =
            scrapedEvents.map { scraped ->
                scraped.toEventEntity(
                    venueId,
                    venueSlug,
                    eventSourceId,
                    existingBySourceId[scraped.sourceId],
                    discriminators[scraped.sourceId],
                    licences
                )
            }
        val (changed, unchanged) = partitionByChanged(entities, existingBySourceId)
        val savedEvents =
            if (changed.isNotEmpty()) {
                eventRepository.saveAll(changed).toList() + unchanged
            } else {
                unchanged
            }

        val touchedArtistIds = associationSyncService.resolveAndSyncAssociations(savedEvents, scrapedEvents)

        // Only changed/new events are logged here; unchanged ones already are, in partitionByChanged.
        var inserted = 0
        changed.forEach { saved ->
            val existed = existingBySourceId.containsKey(saved.sourceId)
            if (!existed) inserted++
            logger.at(Level.DEBUG) {
                message = "${if (existed) "Updated" else "Created"} event '${saved.title}'"
                payload = mapOf(LogFields.EVENT_ID to saved.id, LogFields.EVENT_SOURCE_ID to saved.sourceId)
            }
        }
        return UpsertOutcome(
            inserted = inserted,
            updated = changed.size - inserted,
            skipped = unchanged.size,
            touchedArtistIds = touchedArtistIds
        )
    }

    /**
     * Removes duplicate events from the scraped list, keeping a second sitting. Keyed on date +
     * title + start time (the venue is the same within one import). The start time separates the
     * same event published twice (SO36's combi ticket beside its day-one ticket, 19:30 both, first
     * wins) from two sittings of one production (Theater im Delphi's Schwanensee at 15:00 and
     * 20:00, both kept, [slugDiscriminators] giving each its own slug). No start time collapses to
     * one.
     *
     * A repeated `sourceId` collapses too, whatever the times say: `event.source_id` is `UNIQUE`,
     * and several scrapers key on the show and date rather than the session (Admiralspalast:
     * `admiralspalast:mamma-mia-…-2027-09-18`). Without this guard `saveAll` issues two UPDATEs to
     * one row, last write wins, and the slug flips every import. Recovering those sittings re-keys
     * that venue's whole history (#333).
     */
    private fun deduplicateScrapedEvents(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val seenIds = mutableSetOf<String>()
        val seenKeys = mutableSetOf<String>()
        return events.filter { event ->
            val isNew = seenIds.add(event.sourceId) && seenKeys.add(dedupKey(event))
            if (!isNew) {
                logger.at(Level.WARN) {
                    message = "Skipping duplicate event '${event.title}' on ${event.eventDate}"
                    payload = mapOf(LogFields.EVENT_SOURCE_ID to event.sourceId)
                }
            }
            isNew
        }
    }

    /** Date + title + start time — see [deduplicateScrapedEvents] for why the time is in the key. */
    private fun dedupKey(event: ScrapedEvent): String =
        SlugGenerator.slugify("${event.eventDate}-${event.title}") + "@" + event.startTime?.format(SLUG_TIME).orEmpty()

    /**
     * The slug discriminator each event needs, keyed by `sourceId`; absent for events that need
     * none. `event.slug` is `UNIQUE` and built from date + venue + title, so two sittings collide on
     * insert; only the full scrape can see the collision, so it is computed here and handed to
     * [ScrapedEvent.toEventEntity]. Every member of a colliding group is suffixed, including the
     * first: suffixing only the later ones would read as if one were the real event, and which got
     * the bare slug would depend on page order, so a reordered listing would swap two public URLs.
     */
    private fun slugDiscriminators(events: List<ScrapedEvent>): Map<String, String> =
        events
            .groupBy { SlugGenerator.slugify("${it.eventDate}-${it.title}") }
            .filterValues { group -> group.size > 1 }
            .values
            .flatten()
            .mapNotNull { event -> event.startTime?.let { event.sourceId to it.format(SLUG_TIME) } }
            .toMap()

    /**
     * Removes future events previously imported from this source that are no longer listed. Only
     * tomorrow up to the latest scraped date is considered, so events on pages we did not fetch
     * survive, and past events are always preserved. Tomorrow, not today: many venues stop listing
     * an event once the day begins, so `today` would delete same-day events that are happening. A
     * genuinely cancelled today-event stays for at most a few hours until it is past.
     *
     * @param scrapedEvents the current scrape, for the date range and the set of known sourceIds.
     * @param eventSourceId the owning [EventSourceEntity]'s id, to query by FK.
     */
    private suspend fun removeStaleEvents(
        scrapedEvents: List<ScrapedEvent>,
        eventSourceId: Long
    ) {
        if (scrapedEvents.isEmpty()) return

        val tomorrow = LocalDate.now(clock).plusDays(1)
        val maxScrapedDate = scrapedEvents.maxOf { it.eventDate }
        val scrapedSourceIds = scrapedEvents.map { it.sourceId }.toSet()

        // All events from this source within the cleanup window, from tomorrow (KDoc).
        val existingEvents =
            eventRepository
                .findByEventSourceIdAndEventDateBetween(
                    eventSourceId = eventSourceId,
                    fromDate = tomorrow,
                    toDate = maxScrapedDate
                ).toList()

        val staleEvents = existingEvents.filter { it.sourceId !in scrapedSourceIds }

        if (staleEvents.isNotEmpty()) {
            val staleIds = staleEvents.mapNotNull { it.id }
            eventRepository.deleteByIdIn(staleIds)
            staleEvents.forEach { event ->
                logger.at(Level.INFO) {
                    message = "Removed stale event '${event.title}' on ${event.eventDate}"
                    payload = mapOf(LogFields.EVENT_ID to event.id, LogFields.EVENT_SOURCE_ID to event.sourceId)
                }
            }
            logger.info { "Removed ${staleEvents.size} stale event(s) no longer listed on event source $eventSourceId" }
        }
    }

    /**
     * Partitions built entities into changed-or-new and identical to their database row, so only the
     * former are saved.
     *
     * @return a pair of (changed/new entities, unchanged entities).
     */
    private fun partitionByChanged(
        entities: List<EventEntity>,
        existingBySourceId: Map<String, EventEntity>
    ): Pair<List<EventEntity>, List<EventEntity>> {
        val changed = mutableListOf<EventEntity>()
        val unchanged = mutableListOf<EventEntity>()

        for (entity in entities) {
            val existing = existingBySourceId[entity.sourceId]
            if (existing == null || !entity.contentEquals(existing)) {
                changed.add(entity)
            } else {
                unchanged.add(entity)
                logger.debug { "Skipping unchanged event '${entity.title}' (sourceId=${entity.sourceId})" }
            }
        }

        if (unchanged.isNotEmpty()) {
            logger.info { "Skipped ${unchanged.size} unchanged event(s), saving ${changed.size} changed/new event(s)" }
        }

        return changed to unchanged
    }

    /**
     * Whether this entity has the same business-relevant content as [other]: audit fields (`id`,
     * `createdAt`, `updatedAt`) normalised, then data class `equals()`, so new fields are covered
     * automatically. An extension in the scraper module rather than an override on [EventEntity],
     * which would break Spring Data R2DBC identity semantics.
     */
    private fun EventEntity.contentEquals(other: EventEntity): Boolean = copy(id = other.id, createdAt = other.createdAt, updatedAt = other.updatedAt) == other

    private companion object {
        /** `20:00` → `2000`: colon-free so it survives slugification as one token, not two. */
        val SLUG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")
    }
}

/**
 * What one source's upsert did, split the way `importer.events.written` is tagged. "42 events"
 * is the same number whether the venue published a fresh programme or nothing changed, and
 * telling those apart is the difference between a working importer and one silently scraping a
 * redesigned page (#415, ADR-015). `skipped` is change detection reporting that it worked.
 */
data class UpsertOutcome(
    /** Events that did not exist and were written. */
    val inserted: Int,
    /** Events that existed and whose content had changed. */
    val updated: Int,
    /** Events that existed and were byte-identical, so no UPDATE was issued. */
    val skipped: Int,
    /**
     * Scraped events discarded as already past (#982). Carried out because `importer.events.dropped`
     * is tagged by source slug, which `EventImportService` holds.
     */
    val droppedPast: Int = 0,
    /** Scraped events discarded as duplicates within one scrape (#982). */
    val droppedDuplicate: Int = 0,
    /**
     * The artist rows this run billed, created or found, for the MusicBrainz sweep that runs after
     * the commit over what the import touched (#1567).
     */
    val touchedArtistIds: Set<Long> = emptySet()
) {
    /**
     * Every event the run touched, what still reaches `event_source.last_event_count`.
     */
    val total: Int get() = inserted + updated + skipped

    /**
     * Everything the run threw away before writing. Not added to [total], which feeds
     * `event_source.last_event_count`: a dropped event holds nothing.
     */
    val dropped: Int get() = droppedPast + droppedDuplicate
}
