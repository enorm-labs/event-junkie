package de.norm.events.scraper

import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.licence.SourceLicences
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Handles the persistence pipeline for scraped events: deduplication, upsert, and
 * stale event cleanup.
 *
 * Association management (artist/promoter resolution and join-table syncing) is
 * delegated to [AssociationSyncService] to keep this service focused on event-level
 * persistence concerns. Called within a transactional boundary managed by the caller —
 * it does not manage its own transactions.
 */
@Service
class EventUpsertService(
    private val eventRepository: EventRepository,
    private val associationSyncService: AssociationSyncService,
    /** Injected clock for deterministic time in tests. Defaults to system UTC clock in production. */
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Deduplicates, upserts, and cleans up stale events for a single event source. Call it within a
     * transactional boundary so partial failures roll back cleanly. The pipeline:
     * 1. Drop scraped events dated before today (see [dropPastEvents]).
     * 2. Deduplicate scraped events by generated slug (date + title).
     * 3. Remove stale future events no longer listed on the source website.
     * 4. Upsert events into the database (insert new, update existing by `sourceId`).
     * 5. Resolve and sync artist/promoter associations (delegated to [AssociationSyncService]).
     *
     * **Steps 3 and 4 are in this order deliberately, and swapping them breaks whole imports.**
     * `event.slug` is `UNIQUE` and derived from date + venue + title, *not* from `sourceId`, so a
     * stale row can be sitting on the slug an incoming row needs. That happens whenever a venue
     * re-publishes an event under a new id: SO36 listed its two-day festival's combi ticket
     * (`so36:90006`) on the opening date, then added a day-one ticket (`so36:93090`) with the same
     * title and date. Step 2 keeps the first by page order, step 3 recognises the other as stale —
     * but with the upsert first, the `INSERT` hits the old row's slug and the `executeMany` batch
     * fails, taking **all 111 SO36 events** with it rather than the one row. Deleting first frees the
     * slug; both steps are inside the caller's transaction, so a later failure still rolls the
     * deletion back.
     *
     * @param scrapedEvents the raw events from the scraper; may contain duplicates.
     * @return what the upsert did, split by operation. [UpsertOutcome.total] is what the source's
     *   `lastEventCount` records.
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
        // Cleanup runs BEFORE the upsert, and the order is load-bearing — see the KDoc note.
        removeStaleEvents(uniqueEvents, eventSourceId)
        return upsertEvents(uniqueEvents, venueId, venueSlug, eventSourceId, licences)
    }

    /**
     * Drops scraped events dated before today, keeping today onward.
     *
     * Calendar-style sources publish the venue's whole standing programme — including
     * shows that have already happened (a widget returning the full calendar, or a CMS
     * page that leaves recently-passed nights listed). Because [removeStaleEvents] never
     * prunes past-dated rows (it preserves them for historical records), re-importing such
     * a source would otherwise resurrect stale events on every run. Filtering here — the
     * single funnel every source flows through — stops that universally.
     *
     * This is the ingestion-side dual of [removeStaleEvents]'s cutoff: that method keeps
     * the future as the live window on cleanup; this one does the same on intake. Same-day
     * events are kept (the show may still be running), matching the `tomorrow` lower bound
     * used for cleanup. Existing past-dated rows are untouched — they are simply not
     * re-upserted, so nothing is lost for a source scraped regularly (events age into the
     * past only after they were first imported while still upcoming).
     *
     * @param scrapedEvents the raw events from the scraper.
     * @param eventSourceId the database ID of the [EventSourceEntity] that owns these events, used for logging.
     * @return the scraped events dated today or later.
     */
    private fun dropPastEvents(
        scrapedEvents: List<ScrapedEvent>,
        eventSourceId: Long
    ): List<ScrapedEvent> {
        val today = LocalDate.now(clock)
        val (upcoming, past) = scrapedEvents.partition { !it.eventDate.isBefore(today) }
        if (past.isNotEmpty()) {
            logger.info { "Dropped ${past.size} past event(s) from event source $eventSourceId" }
        }
        return upcoming
    }

    /**
     * Upserts pre-deduplicated scraped events into the database.
     *
     * For each event, checks if an event with the same `sourceId` already
     * exists. If so, compares the built entity against the existing row and
     * only saves it when business-relevant fields have changed — unchanged
     * events are skipped to avoid unnecessary UPDATE statements and inflated
     * `updated_at` timestamps. New events are always inserted. Artist and
     * promoter associations are resolved and synced by [AssociationSyncService].
     *
     * @return what happened, split into inserted / updated / skipped. The split is not extra work:
     *   the insert-vs-update distinction was already being computed for the debug log below, and
     *   `skipped` is exactly the `unchanged` partition change detection already produces (#415).
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

        // 1. Build all event entities in memory, skip unchanged ones, and bulk-save only changes.
        //    This avoids unnecessary UPDATE statements and inflated updated_at timestamps for events
        //    where the scraped data hasn't changed since the last import.
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

        // 2. Resolve artists/promoters and sync associations (delegated to AssociationSyncService)
        associationSyncService.resolveAndSyncAssociations(savedEvents, scrapedEvents)

        // 3. Log upsert results (only for changed/new events — unchanged ones are already logged in partitionByChanged)
        //    and count the same distinction for `importer.events.written{operation}` while it is in hand.
        var inserted = 0
        changed.forEach { saved ->
            val existed = existingBySourceId.containsKey(saved.sourceId)
            if (!existed) inserted++
            logger.debug { "${if (existed) "Updated" else "Created"} event '${saved.title}' (sourceId=${saved.sourceId}, id=${saved.id})" }
        }
        return UpsertOutcome(inserted = inserted, updated = changed.size - inserted, skipped = unchanged.size)
    }

    /**
     * Removes duplicate events from the scraped list — but keeps a second *sitting*.
     *
     * Duplicates are keyed on date + title + **start time**. Within one import every event belongs to
     * the same venue, so the venue slug is deliberately omitted from the key.
     *
     * The start time separates the two shapes that look identical on date and title:
     *  - **The same event, published twice.** SO36 sells a festival combi ticket beside its day-one
     *    ticket — same title, date and 19:30 start. Only one night happens, so the first wins.
     *  - **Two sittings of one production.** A matinee and an evening show genuinely are two events:
     *    Theater im Delphi bills *Schwanensee* at 15:00 and 20:00 on one day. Both are kept, and
     *    [slugDiscriminators] gives each its own slug.
     *
     * An event with no start time cannot be a distinguishable sitting, so a group of those collapses
     * to one — the conservative direction for a venue that publishes no times at all.
     *
     * **A repeated `sourceId` collapses too, whatever the times say.** `event.source_id` is `UNIQUE`,
     * so two scraped events sharing one are one row by identity. Several scrapers key on the show and
     * date rather than the session (Admiralspalast: `admiralspalast:mamma-mia-…-2027-09-18`), so their
     * sittings arrive under one id. Without this guard both entities get the same database id and
     * `saveAll` issues two UPDATEs to one row: no error, last write wins, slug flips every import.
     *
     * Recovering those sittings is a per-scraper change — the session time in the `sourceId` — which
     * re-keys that venue's whole history. See #333.
     */
    private fun deduplicateScrapedEvents(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val seenIds = mutableSetOf<String>()
        val seenKeys = mutableSetOf<String>()
        return events.filter { event ->
            val isNew = seenIds.add(event.sourceId) && seenKeys.add(dedupKey(event))
            if (!isNew) {
                logger.warn { "Skipping duplicate event '${event.title}' on ${event.eventDate} (sourceId=${event.sourceId})" }
            }
            isNew
        }
    }

    /** Date + title + start time — see [deduplicateScrapedEvents] for why the time is in the key. */
    private fun dedupKey(event: ScrapedEvent): String =
        SlugGenerator.slugify("${event.eventDate}-${event.title}") + "@" + (event.startTime?.format(SLUG_TIME) ?: "")

    /**
     * The slug discriminator each event needs, keyed by `sourceId`; absent for events that need none.
     *
     * `event.slug` is `UNIQUE` and built from date + venue + title, so two sittings of one
     * production on one day collide on insert without one. Only the full scrape can see that a
     * collision exists, which is why this is computed
     * here and handed to [ScrapedEvent.toEventEntity] rather than being decided at the boundary.
     *
     * **Every member of a colliding group is suffixed, including the first.** Suffixing only the
     * later ones would leave a matinee at `…/schwanensee` and its evening show at
     * `…/schwanensee-2000`, which reads as if one were the real event; and which of the two got
     * the bare slug would then depend on page order, so a venue reordering its listing would
     * silently swap two public URLs. Slugs outside a colliding group are untouched.
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
     * Removes future events that were previously imported from this source but are
     * no longer listed on the venue's website (e.g. cancelled or removed events).
     *
     * Only events from tomorrow up to the latest scraped date are considered — this
     * prevents deleting events on pages we didn't fetch (e.g. when only page 1 of a
     * paginated listing is scraped). Past events are always preserved for historical
     * records regardless of whether they still appear on the source website.
     *
     * **Why tomorrow, not today?** Many venue websites naturally stop listing events
     * once the day begins (showing only "upcoming" events from tomorrow onward). Using
     * `today` as the lower bound would incorrectly delete same-day events that are
     * actually happening but simply no longer appear in the listing. Starting from
     * tomorrow avoids these false deletions. The trade-off is that a genuinely
     * cancelled today-event stays in the DB for at most a few hours until it becomes
     * a past event — which we preserve for historical records anyway.
     *
     * @param scrapedEvents the events from the current scrape (used to determine
     *   the date range and the set of known sourceIds).
     * @param eventSourceId the database ID of the [EventSourceEntity] that owns these events,
     *   used to query by FK instead of text-pattern matching.
     */
    private suspend fun removeStaleEvents(
        scrapedEvents: List<ScrapedEvent>,
        eventSourceId: Long
    ) {
        if (scrapedEvents.isEmpty()) return

        val tomorrow = LocalDate.now(clock).plusDays(1)
        val maxScrapedDate = scrapedEvents.maxOf { it.eventDate }
        val scrapedSourceIds = scrapedEvents.map { it.sourceId }.toSet()

        // Find all events from this source within the cleanup window via FK.
        // Starts from tomorrow to avoid deleting same-day events that venues
        // may have simply stopped listing — see KDoc for rationale.
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
                logger.info { "Removed stale event '${event.title}' on ${event.eventDate} (sourceId=${event.sourceId}, id=${event.id})" }
            }
            logger.info { "Removed ${staleEvents.size} stale event(s) no longer listed on event source $eventSourceId" }
        }
    }

    /**
     * Partitions built entities into those that actually changed (or are new) vs. those identical
     * to their existing database row. Only changed/new entities need to be saved, avoiding
     * unnecessary UPDATE statements and inflated `updated_at` timestamps.
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
     * Checks whether this entity has the same business-relevant content as [other].
     *
     * Normalizes audit fields (`id`, `createdAt`, `updatedAt`) before comparing via
     * the data class `equals()`, so only actual data changes are detected. Because
     * `equals()` covers all constructor properties, newly added fields are automatically
     * included without manual maintenance.
     *
     * This extension lives in the scraper module (not on [EventEntity] itself) because
     * content-based change detection is a scraper concern. Overriding `equals()`/`hashCode()`
     * on the entity would break Spring Data R2DBC identity semantics and collection behavior.
     */
    private fun EventEntity.contentEquals(other: EventEntity): Boolean = copy(id = other.id, createdAt = other.createdAt, updatedAt = other.updatedAt) == other

    private companion object {
        /** `20:00` → `2000`: colon-free so it survives slugification as one token, not two. */
        val SLUG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")
    }
}

/**
 * What one source's upsert did to the database, split the way `importer.events.written` is tagged.
 *
 * It exists because the count alone cannot answer the question the metric is for. "42 events" is the
 * same number whether the venue published a fresh programme or nothing changed at all — and telling
 * those apart is the difference between a working importer and one that has been silently scraping a
 * redesigned page for a fortnight (#415, ADR-015). `skipped` is therefore a *result*, not noise: it
 * is change detection reporting that it worked.
 */
data class UpsertOutcome(
    /** Events that did not exist and were written. */
    val inserted: Int,
    /** Events that existed and whose content had changed. */
    val updated: Int,
    /** Events that existed and were byte-identical, so no UPDATE was issued. */
    val skipped: Int
) {
    /**
     * Every event the run touched.
     *
     * This is what `upsertAndCleanup` returned before the split, and it is what still reaches
     * `event_source.last_event_count` — so the source's recorded count means exactly what it always
     * did.
     */
    val total: Int get() = inserted + updated + skipped
}
