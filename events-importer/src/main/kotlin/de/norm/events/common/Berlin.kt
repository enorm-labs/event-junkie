package de.norm.events.common

import java.time.ZoneId

/**
 * The wall clock every venue here programmes in: to read an epoch or offset-stamped instant as
 * the venue's local time, and to give a `Clock` the zone whose "today" decides a date.
 */
val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
