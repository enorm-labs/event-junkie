package de.norm.events.scraper.havanna

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * A single undated, weekly recurring Havanna club night, as one of the venue's `/wednesday`,
 * `/friday`, `/saturday` pages describes it: everything a night needs except a date, which
 * [toScrapedEvents] supplies one per week over a rolling horizon.
 *
 * @see HavannaDetailPageScraper for the parsing that produces this.
 */
data class HavannaWeeklyNight(
    /** Weekday this night runs on, derived from the page's URL path (`/friday` → [DayOfWeek.FRIDAY]). */
    val dayOfWeek: DayOfWeek,
    /** Last path segment of the night's page URL (`friday`) — the stable identity used in `sourceId`. */
    val slug: String,
    /** The night's name, e.g. "Saturdays @ HAVANNA". */
    val title: String,
    /** The night's tagline, e.g. "Party auf 3 Dancefloors!". */
    val subtitle: String? = null,
    /** Floor-by-floor programme, times, and pricing prose, one source paragraph per line. */
    val description: String? = null,
    /** Raw floor genres joined for the event's display genre, e.g. "Reggaeton, Latin-Pop, Hip Hop, RnB & Oldschool". */
    val genre: String? = null,
    /** When the party starts, from an explicit "Start:"/"Party:" line or the footer's opening hours. */
    val startTime: LocalTime? = null,
    /** Door price ("Entrance Fee: 14,00 €"); Havanna sells no presale tickets. */
    val priceBoxOffice: BigDecimal? = null,
    /** Poster for the night, from the detail page or the overview teaser. */
    val imageUrl: String? = null,
    /** The night page's URL — every generated occurrence points back at it. */
    val sourceUrl: String,
    /**
     * First day of an announced closure ("WIR SIND AB DEM 01.07.2026 IN DER SOMMERPAUSE!"), or
     * `null`; occurrences on or after it are not generated.
     */
    val pauseFrom: LocalDate? = null
) {
    /**
     * Expands this night into one dated [ScrapedEvent] per week from the next [dayOfWeek] on or
     * after today, for [weeks] weeks. Every import regenerates the same rolling window, idempotent
     * through the stable `sourceId` (`havanna:<date>-<slug>`); occurrences that roll out are
     * cleaned up as stale by `EventUpsertService`. Occurrences on or after [pauseFrom] are omitted,
     * the venue giving no resume date.
     *
     * @param clock clock supplying "today"; override in tests.
     * @param weeks how many weekly occurrences to generate.
     */
    fun toScrapedEvents(
        clock: Clock,
        weeks: Int = OCCURRENCE_WEEKS
    ): List<ScrapedEvent> {
        val firstOccurrence = LocalDate.now(clock).with(TemporalAdjusters.nextOrSame(dayOfWeek))
        return (0 until weeks)
            .map { firstOccurrence.plusWeeks(it.toLong()) }
            .filter { pauseFrom == null || it < pauseFrom }
            .map { date -> toScrapedEvent(date) }
    }

    private fun toScrapedEvent(date: LocalDate): ScrapedEvent =
        ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = description,
            // Every Havanna night is a resident-DJ dance party.
            eventType = EventType.PARTY.name,
            eventDate = date,
            startTime = startTime,
            imageUrl = imageUrl,
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.HAVANNA.sourceIdPrefix}$date-$slug",
            genre = genre,
            // The door price is the only price the venue quotes — there is no presale.
            priceBoxOffice = priceBoxOffice
            // priceNote stays null: the only pricing aside is "(Ladies von 22:00 – 23:00 for free!)", and
            // `detectFree` would read the "free" token as free entry for the whole night. It lives in
            // `description`.
        )

    companion object {
        /**
         * Weekly occurrences per night, ~2 months: deep enough for a month-ahead view, shallow enough
         * to stay a plausible assertion derived from a standing schedule.
         */
        const val OCCURRENCE_WEEKS: Int = 8
    }
}

/** English weekday page paths Havanna uses for its resident nights, mapped to the weekday they run on. */
private val WEEKDAY_PATHS: Map<String, DayOfWeek> =
    mapOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY
    )

/**
 * The last path segment of a night URL (`https://www.havanna-berlin.de/friday` to `friday`),
 * the night's stable identity; the headline on the page changes.
 */
internal fun havannaNightSlug(url: String): String =
    URI(url)
        .path
        .trim('/')
        .substringAfterLast('/')
        .lowercase()

/**
 * The weekday a night page describes, from its URL path, or `null` when the path names none
 * (the `/events` overview, the "‹ Back to Events" link). The only machine-readable weekday, and
 * the filter that tells night links from the site's other buttons.
 */
internal fun havannaWeekdayFromUrl(url: String): DayOfWeek? = WEEKDAY_PATHS[havannaNightSlug(url)]

/**
 * The weekday an English day name refers to ("Saturday" to [DayOfWeek.SATURDAY]), for the
 * footer's opening-hours lines.
 */
internal fun havannaWeekdayFromName(name: String): DayOfWeek? = WEEKDAY_PATHS[name.trim().lowercase()]
