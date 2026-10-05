package de.norm.events.common

import java.time.LocalDateTime

/**
 * The span the venue and promoter lists count and sort by (#2694): events whose effective start
 * ([AssumedStartTime]) is from now to 30 days on, in Berlin time. A source that publishes a year
 * ahead and one that publishes two weeks then compare on the same span.
 */
object NextThirtyDays {
    const val DAYS = 30L

    /**
     * The condition on the event row aliased `e`. The day bounds repeat the window on `event_date`
     * alone, so the date index can prune before the start is computed.
     */
    val SQL: String =
        "e.event_date BETWEEN :windowFirstDay AND :windowLastDay " +
            "AND e.event_date + ${AssumedStartTime.SQL_EFFECTIVE_START} >= :windowFrom " +
            "AND e.event_date + ${AssumedStartTime.SQL_EFFECTIVE_START} < :windowTo"

    /** The bind values of [SQL] for a window that opens at [now]. */
    fun params(now: LocalDateTime): Map<String, Any> {
        val end = now.plusDays(DAYS)
        return mapOf(
            "windowFirstDay" to now.toLocalDate(),
            "windowLastDay" to end.toLocalDate(),
            "windowFrom" to now,
            "windowTo" to end
        )
    }
}
