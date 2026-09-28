package de.norm.events.scraper

import de.norm.events.slug.SlugGenerator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Each billed act with the start and end of its running-order slot (#2002), for a venue whose
 * listing bills the lineup and whose event page times it. A slot is matched by slug and floor,
 * then by slug alone, so an act on two floors keeps both sets and a floor renamed between the
 * pages still matches. A slot that names no billed act goes to [onUnmatched] and is dropped: the
 * lineup stays the listing's.
 */
fun List<ScrapedArtist>.withSetTimesFrom(
    runningOrder: List<ScrapedArtist>,
    onUnmatched: (ScrapedArtist) -> Unit = {}
): List<ScrapedArtist> {
    if (runningOrder.isEmpty()) return this
    val byFloor = runningOrder.groupBy { SlugGenerator.slugify(it.name) to it.stage }
    val byName = runningOrder.groupBy { SlugGenerator.slugify(it.name) }
    val billed = map { SlugGenerator.slugify(it.name) }.toSet()
    runningOrder.filter { SlugGenerator.slugify(it.name) !in billed }.forEach(onUnmatched)
    return map { act ->
        val slug = SlugGenerator.slugify(act.name)
        val slot = byFloor[slug to act.stage]?.first() ?: byName[slug]?.first()
        slot?.let { act.copy(setStart = it.setStart, setEnd = it.setEnd) } ?: act
    }
}

/** A set that starts before this belongs to the night before, as a 04:30 set does on a Saturday. */
val NIGHT_ENDS: LocalTime = LocalTime.of(6, 0)

/**
 * A running order's printed clock times as instants, one slot at a time and in the venue's order
 * (#2013). A slot earlier than the one before it has crossed midnight, and an end at or before its
 * start is the next day's. A first slot earlier than [dayBreak] is already past midnight; with no
 * [dayBreak], the first slot is on [night].
 */
class RunningOrderClock(
    night: LocalDate,
    dayBreak: LocalTime? = null
) {
    private var day = night
    private var previous: LocalTime? = dayBreak

    fun slot(
        start: LocalTime,
        end: LocalTime? = null
    ): Pair<Instant, Instant?> {
        if (previous?.let { start < it } == true) day = day.plusDays(1)
        previous = start
        val endDay = if (end != null && end <= start) day.plusDays(1) else day
        return day.atTime(start).atZone(BERLIN).toInstant() to end?.let { endDay.atTime(it).atZone(BERLIN).toInstant() }
    }
}
