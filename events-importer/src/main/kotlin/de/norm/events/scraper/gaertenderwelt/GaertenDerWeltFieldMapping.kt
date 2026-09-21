package de.norm.events.scraper.gaertenderwelt

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.collapseExhibitionRuns
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Field mapping shared by the Gärten der Welt scrapers: the park's category vocabulary and the
// breadth filter on it, the badges it writes into titles, and the identity, date and start time
// read out of the detail URL. Every case is asserted in GaertenDerWeltFieldMappingTest.

/**
 * The park's category labels, passed to [mapEventType][de.norm.events.scraper.mapEventType].
 * Every label is plural or compound where the shared table is singular ("Konzerte",
 * "Ausstellungen", "Open-Air Kino"), and the filter checkboxes spell them differently from the
 * listing ("Sport & Tanz" vs. "Sport/Tanz"), so both are covered. "Unser Tipp" is an editorial
 * highlight, not a format, and falls through to title-based inference.
 */
val GAERTEN_DER_WELT_CATEGORY_SYNONYMS: Map<String, String> =
    mapOf(
        "konzerte" to EventType.CONCERT.name,
        "bühne/theater" to EventType.SHOW.name,
        "buehne/theater" to EventType.SHOW.name,
        "open-air kino" to EventType.SCREENING.name,
        "ausstellungen" to EventType.EXHIBITION.name,
        "parkfeste" to EventType.FESTIVAL.name
    )

/**
 * The park-activity formats this importer leaves out, matched as substrings of the raw category
 * text so a row with several categories is excluded on any one. The breadth decision the source
 * inventory left open: `/events/veranstaltungen/` is the programme of a park, and of 41 upcoming
 * rows 28 were guided tours, craft workshops, yoga and qigong, environmental education and
 * drop-in handicraft afternoons, which would present Gärten der Welt as a tour operator. What
 * remains is what the park stages: Arena concerts, open-air cinema, park festivals, exhibitions
 * and the evening formats it files under no category. The house decides what kind of night it
 * is, the rule Bar jeder Vernunft set. A row with no category is kept: the park uses the empty
 * category for its games night and quiz show.
 */
private val PARK_ACTIVITY_PATTERN =
    Regex(
        """führung|fuehrung|workshop|sport|tanz|umweltbildung|infoveranstaltung|mitmachaktion""",
        RegexOption.IGNORE_CASE
    )

/**
 * Whether a row's raw [category] text is staged programme rather than a participation format
 * ([PARK_ACTIVITY_PATTERN]). A blank category is in scope.
 */
fun isProgrammeCategory(category: String?): Boolean = category.isNullOrBlank() || !PARK_ACTIVITY_PATTERN.containsMatchIn(category)

/**
 * A leading badge the park writes into the title, having no status field: `AUSGEBUCHT:` for
 * fully booked, `ABGESAGT:` for cancelled, `NEUER TERMIN!` for one already moved to the listed
 * date. Word-anchored to the title start, so an act whose name contains one of these is
 * untouched.
 */
private val TITLE_BADGE_PATTERN =
    Regex("""^\s*(ausgebucht|ausverkauft|abgesagt|verschoben|verlegt|neuer\s+termin)\b\s*[-–—:!.]*\s*""", RegexOption.IGNORE_CASE)

/** The badges that mean "no tickets left" rather than a change to the event's schedule. */
private val SOLD_OUT_BADGES = setOf("ausgebucht", "ausverkauft")

/** Reads the badge leading [title], lowercased, or `null` when it carries none. */
private fun titleBadge(title: String): String? =
    TITLE_BADGE_PATTERN
        .find(title)
        ?.groupValues
        ?.get(1)
        ?.lowercase()

/** Whether [title] is badged as fully booked. */
fun isSoldOutTitle(title: String): Boolean = titleBadge(title) in SOLD_OUT_BADGES

/**
 * Reads the [EventStatus] a title badge announces, defaulting to [EventStatus.SCHEDULED]. A
 * sold-out badge is a flag, not a status ([parseEventStatus]), and `NEUER TERMIN!` announces a
 * move that has already happened, so neither changes the status.
 */
fun gaertenDerWeltStatus(title: String): String = titleBadge(title)?.let { parseEventStatus(it) } ?: EventStatus.SCHEDULED.name

/**
 * Strips the leading [TITLE_BADGE_PATTERN] and applies [cleanEventTitle], so the stored title
 * and the headliner derived from it is the act alone. A title that is nothing but a badge is
 * returned unchanged.
 */
fun cleanGaertenDerWeltTitle(title: String): String {
    val stripped = title.replaceFirst(TITLE_BADGE_PATTERN, "").trim().ifBlank { title.trim() }
    return cleanEventTitle(stripped).ifBlank { title.trim() }
}

/**
 * The date, start time and stable identity a detail URL carries in its path.
 *
 * @property date the day the event starts.
 * @property startTime the time it starts.
 * @property identity the `<stamp>/<slug>` pair the `sourceId` is built from.
 */
data class GaertenDerWeltEventPath(
    val date: LocalDate,
    val startTime: LocalTime?,
    val identity: String,
    /** The stamp-less `<slug>`: what the days of one exhibition share (#337). */
    val slug: String
)

/**
 * Reads date, start time and identity out of a detail URL such as
 * `…/events/veranstaltungen/detail/2026-08-15_1900/agnes-obel/`, or `null` when the path has no
 * stamp, leaving the caller the listing's German date. TYPO3's `events2` routes every event
 * under a `YYYY-MM-DD_HHmm` stamp from its own start, the most machine-readable date the source
 * publishes (ADR-007 §"Selector Strategy"): the listing's `08.08.2026` omits the time, the
 * detail page's `Samstag, 08.08.` the year, and a multi-day run renders as a range
 * (`01.09.2026 - 01.11.2026`).
 *
 * The slug alone is not the identity: the park reuses one slug across every date of a recurring
 * event (`fuehrung-durch-die-gaerten-der-welt` runs monthly). The exception is an exhibition,
 * listed once per open day under one slug and folded by [collapseExhibitionRuns]. A rescheduled
 * event changes stamp and therefore `sourceId`: the old row is cleaned up as stale, the correct
 * outcome for a different date.
 */
fun parseEventPath(sourceUrl: String): GaertenDerWeltEventPath? =
    EVENT_PATH_PATTERN.find(URI(sourceUrl).path)?.destructured?.let { (stamp, time, slug) ->
        parseIsoDate(stamp)?.let { date ->
            GaertenDerWeltEventPath(date = date, startTime = parseTime(time, STAMP_TIME_FORMATTER), identity = "${stamp}_$time/$slug", slug = slug)
        }
    }

/** The `YYYY-MM-DD_HHmm/<slug>` tail of a detail URL, anchored to the end of the path. */
private val EVENT_PATH_PATTERN = Regex("""(\d{4}-\d{2}-\d{2})_(\d{4})/([^/]+)/?$""")

/** The stamp's bare four-digit time, e.g. `1900` or `0900`. */
private val STAMP_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")
