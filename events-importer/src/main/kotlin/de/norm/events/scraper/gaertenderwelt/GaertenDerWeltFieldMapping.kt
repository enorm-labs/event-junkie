package de.norm.events.scraper.gaertenderwelt

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Field mapping shared by the Gärten der Welt overview and detail scrapers: the park's own
// category vocabulary and the breadth filter built on it, the status/sold-out badges it writes
// into titles, and the identity, date and start time all read out of the detail URL. Every case
// is asserted in GaertenDerWeltFieldMappingTest, which is where the examples live.

/**
 * The park's category labels, passed to [mapEventType][de.norm.events.scraper.mapEventType] as
 * venue-specific synonyms. Every label is plural or compound where the shared singular table is
 * not ("Konzerte", "Ausstellungen", "Open-Air Kino"), and the labels the filter checkboxes offer
 * differ in spelling from the ones the listing renders ("Sport & Tanz" vs. "Sport/Tanz"), so both
 * spellings are covered.
 *
 * "Unser Tipp" is the park's editorial highlight tag rather than a format, so it is deliberately
 * absent and falls through to the title-based inference in the scrapers.
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
 * The park-activity formats this importer deliberately leaves out, matched as substrings of the
 * raw category text so a row carrying several categories is excluded on any one of them.
 *
 * **This is the breadth decision the source inventory left open, and the one line to change to
 * revisit it.** `/events/veranstaltungen/` is the programme of a *park*, not of a stage: of 41
 * upcoming rows at the time of writing, 28 were guided tours through the themed gardens, craft
 * workshops, yoga and qigong sessions, environmental-education slots and drop-in handicraft
 * afternoons — recurring park activities that would swamp the venue's actual programme and
 * present Gärten der Welt as a tour operator. What remains is what the park *stages*: the Arena
 * concerts, the open-air cinema, the park festivals, the exhibitions and the evening formats it
 * files under no category at all.
 *
 * Following the venue's own labels is the same rule Bar jeder Vernunft set — the house decides
 * what kind of night it is — applied here to decide whether a row is programme at all. A row with
 * no category is kept: the park uses the empty category for one-off evening events (its games
 * night, its quiz show), and dropping uncategorised rows would lose them.
 */
private val PARK_ACTIVITY_PATTERN =
    Regex(
        """führung|fuehrung|workshop|sport|tanz|umweltbildung|infoveranstaltung|mitmachaktion""",
        RegexOption.IGNORE_CASE
    )

/**
 * Whether a listing row's raw [category] text is part of the park's staged programme rather than
 * one of its participation formats (see [PARK_ACTIVITY_PATTERN]). A blank or absent category is
 * in scope.
 */
fun isProgrammeCategory(category: String?): Boolean = category.isNullOrBlank() || !PARK_ACTIVITY_PATTERN.containsMatchIn(category)

/**
 * A leading badge the park writes into the event title itself, having no status field of its own:
 * `AUSGEBUCHT:` for a fully booked event, `ABGESAGT:` for a cancelled one, and `NEUER TERMIN!`
 * for one that has already been moved to the date it is now listed under. Word-anchored to the
 * title start with the badge captured, so an act whose name merely contains one of these words is
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
 * Reads the [EventStatus] a title badge announces, defaulting to [EventStatus.SCHEDULED].
 *
 * A sold-out badge is a flag, not a status (the shared [parseEventStatus] contract), and
 * `NEUER TERMIN!` announces a move that has *already happened* — the event is listed under its
 * new date — so neither changes the status.
 */
fun gaertenDerWeltStatus(title: String): String = titleBadge(title)?.let { parseEventStatus(it) } ?: EventStatus.SCHEDULED.name

/**
 * Strips the leading [TITLE_BADGE_PATTERN] badge and applies the shared [cleanEventTitle] tidy-up,
 * so the stored title — and the headliner derived from it — is the act's name alone. A title that
 * is nothing but a badge is returned unchanged rather than emptied.
 */
fun cleanGaertenDerWeltTitle(title: String): String {
    val stripped = title.replaceFirst(TITLE_BADGE_PATTERN, "").trim().ifBlank { title.trim() }
    return cleanEventTitle(stripped).ifBlank { title.trim() }
}

/**
 * The date, start time and stable identity a Gärten der Welt detail URL carries in its path.
 *
 * @property date the day the event starts.
 * @property startTime the time it starts.
 * @property identity the `<stamp>/<slug>` pair the `sourceId` is built from.
 */
data class GaertenDerWeltEventPath(
    val date: LocalDate,
    val startTime: LocalTime?,
    val identity: String
)

/**
 * Reads the date, start time and identity out of a detail URL such as
 * `…/events/veranstaltungen/detail/2026-08-15_1900/agnes-obel/`, or `null` when the path does not
 * carry the stamp (a redesigned routing scheme), leaving the caller to fall back to the listing's
 * German date rendering.
 *
 * TYPO3's `events2` extension routes every event under a `YYYY-MM-DD_HHmm` stamp generated from
 * the event's own start, which makes the URL the most machine-readable date and time the source
 * publishes (ADR-007 §"Selector Strategy"). It beats both renderings on the page: the listing's
 * `08.08.2026` omits the time, the detail page's `Samstag, 08.08.` omits the year, and a
 * multi-day run renders as a range (`01.09.2026 - 01.11.2026`) where the stamp gives the start
 * outright.
 *
 * The slug alone is *not* the identity: the park reuses one slug across every date of a recurring
 * event (`fuehrung-durch-die-gaerten-der-welt` runs monthly), so the stamp is what separates them.
 * The flip side is that a rescheduled event changes stamp and therefore `sourceId` — the old row
 * is cleaned up as stale and the new date inserted, which is the correct outcome for what is
 * genuinely a different date.
 */
fun parseEventPath(sourceUrl: String): GaertenDerWeltEventPath? =
    EVENT_PATH_PATTERN.find(URI(sourceUrl).path)?.destructured?.let { (stamp, time, slug) ->
        parseIsoDate(stamp)?.let { date ->
            GaertenDerWeltEventPath(date = date, startTime = parseTime(time, STAMP_TIME_FORMATTER), identity = "${stamp}_$time/$slug")
        }
    }

/** The `YYYY-MM-DD_HHmm/<slug>` tail of a detail URL, anchored to the end of the path. */
private val EVENT_PATH_PATTERN = Regex("""(\d{4}-\d{2}-\d{2})_(\d{4})/([^/]+)/?$""")

/** The stamp's bare four-digit time, e.g. `1900` or `0900`. */
private val STAMP_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")
