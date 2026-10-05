package de.norm.events.event

/**
 * The slots of the time-of-night filter (#2720). An event with an end matches every slot it runs
 * through, so an open-air from 14:00 to 23:00 is daytime, evening and late; one that runs a day or
 * longer matches all three. An event without an end matches the slot of its stated start, else its
 * doors time. Times are stored as Berlin wall-clock time, so no conversion is needed. The assumed
 * slot of #1384 is not used: a guess would put every timeless party into `late`. A row with neither
 * time matches no slot.
 */
enum class TimeOfDay(
    val slug: String,
    private val from: String,
    private val to: String
) {
    DAYTIME("daytime", "06:00", "18:00"),
    EVENING("evening", "18:00", "22:00"),
    LATE("late", "22:00", "06:00")
    ;

    /** The slot as an SQL condition on the event row `e`. */
    val sql: String
        get() =
            "($STATED_START IS NOT NULL AND CASE WHEN $END IS NULL OR $END <= $START " +
                "THEN ${startsIn()} ELSE ($END - $START >= INTERVAL '24 hours' OR ${overlapsAny()}) END)"

    /** `late` crosses midnight, so it is the one slot that is an `OR`. */
    private fun startsIn(): String =
        if (from < to) {
            "($STATED_START >= ${time(from)} AND $STATED_START < ${time(to)})"
        } else {
            "($STATED_START >= ${time(from)} OR $STATED_START < ${time(to)})"
        }

    /**
     * Whether the run overlaps this slot on the event's day or the next; a run shorter than a day
     * reaches no further. `late` also opens the evening before, for a run that starts after midnight.
     */
    private fun overlapsAny(): String {
        val windows =
            if (from < to) {
                listOf(0 to 0, 1 to 1)
            } else {
                listOf(-1 to 0, 0 to 1, 1 to 2)
            }
        return windows.joinToString(" OR ", "(", ")") { (startDay, endDay) ->
            "($START < (e.event_date + $endDay) + ${time(to)} AND $END > (e.event_date + $startDay) + ${time(from)})"
        }
    }

    companion object {
        fun bySlug(slug: String): TimeOfDay? = entries.firstOrNull { it.slug == slug }
    }
}

private const val STATED_START = "COALESCE(e.start_time, e.doors_time)"

private const val START = "(e.event_date + $STATED_START)"

/**
 * The end. A later end day without a time ends at midnight after it. A same-day end without a time
 * says nothing about the hour, so the row keeps its start's slot.
 */
private const val END =
    "COALESCE(e.end_date + e.end_time, CASE WHEN e.end_date > e.event_date THEN e.end_date + INTERVAL '1 day' END)"

private fun time(clock: String): String = "TIME '$clock'"
