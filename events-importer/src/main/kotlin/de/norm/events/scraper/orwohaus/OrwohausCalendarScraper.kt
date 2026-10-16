package de.norm.events.scraper.orwohaus

import de.norm.events.event.EventType
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.isFestivalTitle
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.orderDoorsBeforeStart
import de.norm.events.scraper.parseLabelledPrices
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure parser for the Events Manager iCalendar feed of the ORWOhaus. **The site's clock is a fixed
 * UTC+1** all year: the feed's UTC times plus one hour are the times the listing prints, also in
 * summer. Workshops are classes, not shows, and are skipped. The weekly-or-so jam session is
 * published years ahead, so events past [HORIZON_MONTHS] are left for a later run. Only a title
 * that bills acts with `mit …` or names a tour mints artists: the house and promoter nights
 * (`NECROMANIA 2026`, `BE NOISE - Live Battle`) name the night, not an act.
 */
class OrwohausCalendarScraper(
    private val clock: Clock
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(calendar: String): List<ScrapedEvent> {
        val horizon = LocalDate.now(clock).plusMonths(HORIZON_MONTHS)
        val entries = calendarEvents(calendar)
        logger.info { "Found ${entries.size} ORWOhaus calendar entr(y/ies)" }
        return entries
            .mapSkippingFailures(logger, "ORWOhaus calendar entry") { parseEntry(it) }
            .filter { it.eventDate <= horizon }
    }

    private fun parseEntry(entry: Map<String, CalendarProperty>): ScrapedEvent? {
        val title = entry["SUMMARY"]?.text?.let(::decodeHtmlEntities).blankToNull()
        val url = entry["URL"]?.value.blankToNull()
        val start = entry["DTSTART"]?.let(::siteTime)
        if (title == null || url == null || start == null) {
            logger.warn { "ORWOhaus calendar entry '$title' lacks a title, URL or start, skipping" }
            return null
        }
        val categories =
            entry["CATEGORIES"]
                ?.text
                .orEmpty()
                .split(',')
                .mapNotNull { it.blankToNull() }
        if (categories.any { normalize(it) == WORKSHOP }) return null
        val end = entry["DTEND"]?.let(::siteTime)?.takeIf { it > start }
        val description = entry["DESCRIPTION"]?.text?.let(::htmlParagraphText)
        val eventType = eventType(title, categories)
        val (doors, startTime) =
            orderDoorsBeforeStart(
                labelledClock(description, DOORS_LABELS),
                labelledClock(description, START_LABELS) ?: start.time
            )
        val prices = parseLabelledPrices(description)
        return ScrapedEvent(
            title = title,
            description = description,
            eventType = eventType,
            eventDate = start.date,
            doorsTime = doors,
            startTime = startTime?.takeUnless { it == doors },
            endDate = end?.date,
            endTime = end?.time,
            room = entry["LOCATION"]?.text?.let(::room),
            imageUrl = entry["ATTACH"]?.takeIf { it.params["FMTTYPE"]?.startsWith("image/") == true }?.value,
            sourceUrl = url,
            sourceId = "${EventSource.ORWOHAUS.sourceIdPrefix}${extractEventSlug(url)}",
            genre = categories.filterNot { normalize(it) in NON_GENRE_CATEGORIES }.joinToString(", ").blankToNull(),
            pricePresale = prices.presale,
            priceBoxOffice = prices.boxOffice,
            artists = artists(title, eventType)
        )
    }

    private fun eventType(
        title: String,
        categories: List<String>
    ): String {
        val keys = categories.map(::normalize)
        return when {
            FESTIVAL in keys || isFestivalTitle(title) -> EventType.FESTIVAL.name
            CONCERT in keys || JAM_SESSION in keys -> EventType.CONCERT.name
            else -> EventType.OTHER.name
        }
    }

    private fun artists(
        title: String,
        eventType: String
    ): List<ScrapedArtist> =
        if (eventType == EventType.CONCERT.name && BILLS_ACTS.containsMatchIn(title)) {
            headlinersFromTitle(title, unpackWithFrame = true)
        } else {
            emptyList()
        }

    /** The room a `LOCATION` names before its address, without the house's own name: `ORWOhaus e.V. - Showroom 104` is `Showroom 104`. */
    private fun room(location: String): String? = location.substringBefore(',').replace(HOUSE_NAME, "").blankToNull()

    private fun siteTime(property: CalendarProperty): SiteTime {
        val raw = property.value.trim()
        if (property.params["VALUE"] == "DATE") return SiteTime(LocalDate.parse(raw, DATE_FORMAT), null)
        val local = LocalDateTime.parse(raw.removeSuffix("Z"), DATE_TIME_FORMAT)
        val onSiteClock = if (raw.endsWith('Z')) local.atOffset(ZoneOffset.UTC).atZoneSameInstant(SITE_OFFSET).toLocalDateTime() else local
        return SiteTime(onSiteClock.toLocalDate(), onSiteClock.toLocalTime())
    }

    private data class SiteTime(
        val date: LocalDate,
        val time: LocalTime?
    ) : Comparable<SiteTime> {
        override fun compareTo(other: SiteTime): Int = compareValuesBy(this, other, { it.date }, { it.time })
    }

    private companion object {
        const val HORIZON_MONTHS = 12L
        val SITE_OFFSET: ZoneOffset = ZoneOffset.ofHours(1)
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        // The feed folds a long CATEGORIES line at a space and loses it, so categories compare without whitespace.
        const val WORKSHOP = "workshop"
        const val CONCERT = "konzert"
        const val JAM_SESSION = "orwojam-session"
        const val FESTIVAL = "orwohausfestival"

        /** The categories that file a night by room, series or screen slot rather than by style. */
        val NON_GENRE_CATEGORIES =
            setOf(
                WORKSHOP,
                CONCERT,
                JAM_SESSION,
                FESTIVAL,
                "aufbildschirmuntenanzeigen",
                "eigeneveranstaltungen",
                "externeveranstaltungen",
                "showroom104",
                "backstagebar",
                "oha"
            )

        val HOUSE_NAME = Regex("""^\s*ORWOhaus(?:\s+e\.V\.)?(?:\s*-\s*)?""", RegexOption.IGNORE_CASE)

        /** A title that bills acts: `<night> mit <acts>`, or `<act> – <name> Tour 2026`. */
        val BILLS_ACTS = Regex("""\smit\s|tour\b""", RegexOption.IGNORE_CASE)

        fun normalize(category: String): String = category.replace(WHITESPACE, "").lowercase()
    }
}
