package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA

/**
 * SQL that is true when an `EVENT_DATE` change only swapped two nights of a series. A weekly night can
 * trade dates between two listings, one per host, and each row then records a move. The night still
 * happens on its old date, so such a change is neither shown nor pushed.
 *
 * [change] and [event] are the SQL aliases of the `event_change` row and of the event it is about.
 */
fun seriesDateSwap(
    change: String,
    event: String
): String =
    "($change.field = 'EVENT_DATE' AND EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event other " +
        "WHERE other.venue_id = $event.venue_id AND other.id <> $event.id " +
        "AND other.event_date = CASE WHEN $change.field = 'EVENT_DATE' THEN $change.old_value::date END " +
        "AND ${seriesKey("other.title")} = ${seriesKey("$event.title")}))"

/** The title without a host suffix (`… - mit Otto F.`), reduced to lower-case letters and digits. */
private fun seriesKey(column: String): String =
    "lower(regexp_replace(regexp_replace($column, '\\s*[-–:]?\\s+(mit|with|feat\\.?)\\s.*$', '', 'i'), '[^[:alnum:]]', '', 'g'))"
