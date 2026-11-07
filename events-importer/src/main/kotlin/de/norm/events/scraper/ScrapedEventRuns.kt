package de.norm.events.scraper

import de.norm.events.event.EventType
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Returns the events that are not over, passing the number dropped to [onDropped]. An event is
 * over after its `endDate`, else after its date (ADR-029), and today counts as not over. Before
 * 06:00 on [clock], last night's event with no stated end is not over either when it started at
 * 22:00 or later, or names no start (#299); the BFF applies the same grace with the assumed
 * slot. `EventUpsertService` is the source of truth; a scraper applies the cutoff earlier only
 * to spare a detail-page fetch. The callback keeps the log at the call site.
 */
fun List<ScrapedEvent>.dropPastEvents(
    clock: Clock,
    onDropped: (Int) -> Unit
): List<ScrapedEvent> {
    val now = LocalDateTime.now(clock)
    val today = now.toLocalDate()
    val graceNight = today.minusDays(1).takeIf { now.toLocalTime() < GRACE_ENDS }
    val (upcoming, past) =
        partition {
            !(it.endDate ?: it.eventDate).isBefore(today) ||
                (it.endDate == null && it.eventDate == graceNight && (it.startTime == null || it.startTime >= LATE_START))
        }
    if (past.isNotEmpty()) onDropped(past.size)
    return upcoming
}

/**
 * Folds the days of an exhibition into one run (ADR-029, #337): a gallery lists a show once per
 * day, linking the same page. [key] names that page as the run's `sourceId`, or null for a row
 * that is not one day of a run, and every `EXHIBITION` row sharing a key becomes one event dated
 * from the first listed day to the last, built on the first day whose page answered. A row already
 * carrying a span widens the fold. A single day stays a single day; every other kind of event
 * passes through, since a festival's days differ in lineup. A listing drops the days already over,
 * so the upsert keeps an earlier stored opening (#2940).
 */
fun List<ScrapedEvent>.collapseExhibitionRuns(key: (ScrapedEvent) -> String?): List<ScrapedEvent> {
    val runKey = { event: ScrapedEvent -> key(event)?.takeIf { event.eventType == EventType.EXHIBITION.name } }
    val runs = mapNotNull { event -> runKey(event)?.let { it to event } }.groupBy({ it.first }, { it.second })
    val folded = mutableSetOf<String>()
    return mapNotNull { event ->
        val k = runKey(event) ?: return@mapNotNull event
        if (!folded.add(k)) return@mapNotNull null
        val days = runs.getValue(k).sortedBy { it.eventDate }
        val opening = days.first().eventDate
        val closing = days.maxOf { it.endDate ?: it.eventDate }
        (days.firstOrNull { !it.detailUnavailable } ?: days.first()).copy(
            sourceId = k,
            eventDate = opening,
            endDate = closing.takeIf { it > opening },
            endTime = null
        )
    }
}

/**
 * Folds each day whose [ScrapedEvent.storedRunId] is in [runIds] into that run, as
 * [collapseExhibitionRuns] does. The day takes EXHIBITION and bills no act, as a day whose page
 * answered does (#2575).
 */
fun List<ScrapedEvent>.foldIntoRuns(runIds: Set<String>): List<ScrapedEvent> =
    if (runIds.isEmpty()) {
        this
    } else {
        map { if (it.storedRunId in runIds) it.copy(eventType = EventType.EXHIBITION.name, artists = emptyList()) else it }
            .collapseExhibitionRuns { it.storedRunId?.takeIf(runIds::contains) }
    }

/** A start at or after this is a night, and gets the grace (#299). */
private val LATE_START: LocalTime = LocalTime.of(22, 0)

/** When last night is over for the importer (#299). The BFF and the frontend share the hour. */
private val GRACE_ENDS: LocalTime = LocalTime.of(6, 0)
