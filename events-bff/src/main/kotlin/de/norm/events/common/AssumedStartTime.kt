package de.norm.events.common

import java.time.LocalTime

/**
 * The start an event is taken to have when the venue published neither a start nor a doors
 * time, by kind of event (#1384). One event in ten has no start, and a club night with no time
 * is a night, not the 23:59 `NULLS LAST` made of it. The list sorts by this slot and the page
 * shows it as a guess (`assumedStartTime`, never `startTime`), both reading this one table.
 *
 * The medians of the events that carry a start (3711 on staging, measured in #1384), with one
 * exception: `PARTY` sits at the top of its p75, because the parties without a time are the
 * clubs, whose doors are 23:00–00:00. `COMEDY` was split out of `SHOW` later (#2261) and was
 * measured on the local database.
 *
 * | type       | rows | median | p25–p75     |
 * |------------|------|--------|-------------|
 * | EXHIBITION |   13 | 09:00  | 09:00–09:00 |
 * | FESTIVAL   |   36 | 17:00  | 15:00–19:00 |
 * | SHOW       |  282 | 19:00  | 19:00–20:00 |
 * | OTHER      |  165 | 19:00  | 17:00–20:00 |
 * | QUIZ       |   22 | 19:30  | 19:00–20:30 |
 * | READING    |   72 | 19:30  | 19:30–20:00 |
 * | COMEDY     |   27 | 20:00  | 19:00–20:00 |
 * | CONCERT    | 1815 | 20:00  | 19:30–20:00 |
 * | SCREENING  |   13 | 20:00  | 19:00–20:30 |
 * | PARTY      |  893 | 22:00  | 19:00–22:30 |
 *
 * Keyed by the `EventType` name rather than the enum, so this `common` object depends on no
 * module and the venue and promoter lists can count by it too (#2694).
 */
object AssumedStartTime {
    private const val OTHER = "OTHER"

    private val BY_TYPE: Map<String, LocalTime> =
        mapOf(
            "EXHIBITION" to LocalTime.of(9, 0),
            "FESTIVAL" to LocalTime.of(17, 0),
            "SHOW" to LocalTime.of(19, 0),
            OTHER to LocalTime.of(19, 0),
            "QUIZ" to LocalTime.of(19, 30),
            "READING" to LocalTime.of(19, 30),
            "COMEDY" to LocalTime.of(20, 0),
            "CONCERT" to LocalTime.of(20, 0),
            "SCREENING" to LocalTime.of(20, 0),
            "PARTY" to LocalTime.of(23, 0)
        )

    /** The `EventType` names the table carries a slot for. */
    val TYPES: Set<String> = BY_TYPE.keys

    /** The slot for an event of [eventType], an `EventType` name; [OTHER]'s for a type the table does not name. */
    fun of(eventType: String): LocalTime = BY_TYPE[eventType.trim().uppercase()] ?: BY_TYPE.getValue(OTHER)

    /** The slot for an event of [eventType] when it has neither time, else null — the guess only where there is no fact. */
    fun guess(
        startTime: LocalTime?,
        doorsTime: LocalTime?,
        eventType: String
    ): LocalTime? = if (startTime == null && doorsTime == null) of(eventType) else null

    /**
     * The same table as a SQL expression over the `event` row aliased `e`, for `ORDER BY` and the 30-day counts; the
     * `ELSE` is [OTHER]'s slot, as `EventType.parseOrDefault` gives an unknown value.
     */
    val SQL_EFFECTIVE_START: String =
        BY_TYPE.entries.joinToString(
            prefix = "COALESCE(e.start_time, e.doors_time, CASE e.event_type ",
            separator = " ",
            postfix = " ELSE TIME '${BY_TYPE.getValue(OTHER)}' END)"
        ) { (type, time) -> "WHEN '$type' THEN TIME '$time'" }
}
