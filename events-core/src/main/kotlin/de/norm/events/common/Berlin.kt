package de.norm.events.common

import java.time.ZoneId

/**
 * The wall clock every venue here programmes in. It decides "today", the local time an epoch or
 * offset-stamped instant is read as, and the time the site shows.
 */
val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
