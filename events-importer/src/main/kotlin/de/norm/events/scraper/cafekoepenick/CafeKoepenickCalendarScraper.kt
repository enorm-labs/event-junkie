package de.norm.events.scraper.cafekoepenick

import de.norm.events.event.EventType
import de.norm.events.scraper.CalendarProperty
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.SiteTime
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.calendarEvents
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.siteTime
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.time.LocalDateTime

/**
 * Pure parser for the Café Köpenick's public calendar feed. The feed is the club's own diary: the
 * weekly Küfa, counselling hours, meetings, workshops and outings sit beside the band nights, and
 * only a title says which is which ([programmeType]). Most band nights are all-day entries, so they
 * carry no start time. The description holds the staff's own notes and is not stored.
 */
class CafeKoepenickCalendarScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        calendar: String,
        feedUrl: String
    ): List<ScrapedEvent> {
        val entries = calendarEvents(calendar)
        logger.info { "Found ${entries.size} Café Köpenick calendar entr(y/ies)" }
        val pageUrl = calendarPageUrl(feedUrl)
        return entries.mapSkippingFailures(logger, "Café Köpenick calendar entry") { parseEntry(it, pageUrl) }
    }

    private fun parseEntry(
        entry: Map<String, CalendarProperty>,
        pageUrl: String
    ): ScrapedEvent? {
        val title = entry["SUMMARY"]?.text?.let(::decodeHtmlEntities).blankToNull()
        val uid = entry["UID"]?.value.blankToNull()
        val start = entry["DTSTART"]?.siteTime()
        if (title == null || uid == null || start == null) {
            logger.warn { "Café Köpenick calendar entry '$title' lacks a title, UID or start, skipping" }
            return null
        }
        val eventType = programmeType(title, entry["DESCRIPTION"]?.text) ?: return null
        val end = entry["DTEND"]?.siteTime()?.let { endOf(start, it) }
        return ScrapedEvent(
            title = title,
            eventType = eventType,
            eventDate = start.date,
            startTime = start.time,
            endDate = end?.date,
            endTime = end?.time,
            sourceUrl = pageUrl,
            sourceId = "${EventSource.CAFE_KOEPENICK.sourceIdPrefix}$uid",
            artists = if (eventType == EventType.CONCERT.name) billedActs(title) else emptyList()
        )
    }

    /** The end that says more than the start: a later time, or the last day of an all-day run, whose `DTEND` is the day after. */
    private fun endOf(
        start: SiteTime,
        end: SiteTime
    ): SiteTime? =
        if (end.time == null) {
            end.copy(date = end.date.minusDays(1)).takeIf { it.date > start.date }
        } else {
            end.takeIf { start.time != null && LocalDateTime.of(end.date, end.time) > LocalDateTime.of(start.date, start.time) }
        }
}

/** A trip the club makes to another stage (`Cafe goes "Future is now" im Schlossplatztheater`): not a night at the club. */
private val OUTING = Regex("""^\s*caf[eé]\s+goes\b""", RegexOption.IGNORE_CASE)

/** The weekly open stage, a concert with no act. */
private val OPEN_STAGE = Regex("""\bopen\s+(?:jam|mic)\b""", RegexOption.IGNORE_CASE)

/** A band bill: `Freshly married + Kalte Knie`, `Muffcase / Bands`, `Raplicat + Band`. */
private val BAND_BILL = Regex("""\+|\bbands?\b""", RegexOption.IGNORE_CASE)

/** The club's word for a band night with no act named yet, in the title or the staff's note. */
private val BANDS = Regex("""\bbands?\b""", RegexOption.IGNORE_CASE)

/** The format words of the club's programme, checked in this order against the title. */
private val FORMATS =
    linkedMapOf(
        "festival" to EventType.FESTIVAL.name,
        "party" to EventType.PARTY.name,
        "tresen" to EventType.PARTY.name,
        "krawall" to EventType.CONCERT.name,
        "stomp" to EventType.CONCERT.name,
        "theater" to EventType.SHOW.name,
        "lesung" to EventType.READING.name
    )

/**
 * The event type of a programme entry, or null for the club's other diary entries (EVENT_SCOPE §3.2).
 * A title must name a format, the open stage or a band bill; the staff's note can name the bands.
 */
internal fun programmeType(
    title: String,
    note: String?
): String? {
    if (OUTING.containsMatchIn(title)) return null
    val lowered = title.lowercase()
    return FORMATS.entries.firstOrNull { (word, _) -> word in lowered }?.value
        ?: EventType.CONCERT.name.takeIf {
            OPEN_STAGE.containsMatchIn(title) || BAND_BILL.containsMatchIn(title) || BANDS.containsMatchIn(note.orEmpty())
        }
}

/** The acts of a `+` or `/` bill, without the bare `Band` that stands for an act not named yet. */
private fun billedActs(title: String): List<ScrapedArtist> =
    if (OPEN_STAGE.containsMatchIn(title)) {
        emptyList()
    } else {
        title
            .split('+', '/')
            .mapNotNull { it.blankToNull() }
            .filterNot { BANDS.matches(it) || isNonArtistName(it) }
            .takeIf { title.contains('+') || title.contains('/') }
            .orEmpty()
            .map { ScrapedArtist(it) }
    }

/** The calendar's own page, `https://kalender.digital/<id>`, from the feed URL `…/ics/<sub-calendar>/<id>/<name>.ics`. */
private fun calendarPageUrl(feedUrl: String): String {
    val segments = URI(feedUrl).path.split('/').filter { it.isNotEmpty() }
    val id = segments.getOrNull(segments.indexOf("ics") + 2)
    return if (id == null) feedUrl else "https://kalender.digital/$id"
}
