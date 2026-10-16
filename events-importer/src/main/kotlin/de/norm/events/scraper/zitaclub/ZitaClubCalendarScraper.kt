package de.norm.events.scraper.zitaclub

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.CalendarProperty
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.calendarEvents
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapSkippingFailures
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure parser for the Events Manager iCalendar feed of the Zita Club. The site's clock is
 * Europe/Berlin and the feed writes local times with that `TZID`, so they are read as printed.
 * A `Geschlossene Veranstaltung` is a private hire and is skipped. The feed carries no category:
 * a night is a party unless its title says otherwise.
 */
class ZitaClubCalendarScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(calendar: String): List<ScrapedEvent> {
        val entries = calendarEvents(calendar)
        logger.info { "Found ${entries.size} Zita Club calendar entr(y/ies)" }
        return entries.mapSkippingFailures(logger, "Zita Club calendar entry") { parseEntry(it) }
    }

    private fun parseEntry(entry: Map<String, CalendarProperty>): ScrapedEvent? {
        val title = entry["SUMMARY"]?.text?.let(::decodeHtmlEntities).blankToNull()
        val url = entry["URL"]?.value.blankToNull()
        val start = entry["DTSTART"]?.let(::siteTime)
        if (title == null || url == null || start == null) {
            logger.warn { "Zita Club calendar entry '$title' lacks a title, URL or start, skipping" }
            return null
        }
        if (PRIVATE_HIRE.containsMatchIn(title)) return null
        val end = entry["DTEND"]?.let(::siteTime)?.takeIf { it > start }
        return ScrapedEvent(
            title = title,
            description = entry["DESCRIPTION"]?.text?.let(::decodeHtmlEntities).blankToNull(),
            eventType = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name } ?: EventType.PARTY.name,
            eventDate = start.date,
            startTime = start.time,
            endDate = end?.date,
            endTime = end?.time,
            imageUrl = entry["ATTACH"]?.takeIf { it.params["FMTTYPE"]?.startsWith("image/") == true }?.value,
            sourceUrl = url,
            sourceId = "${EventSource.ZITA_CLUB.sourceIdPrefix}${extractEventSlug(url)}"
        )
    }

    private fun siteTime(property: CalendarProperty): SiteTime {
        val raw = property.value.trim()
        if (property.params["VALUE"] == "DATE") return SiteTime(LocalDate.parse(raw, DATE_FORMAT), null)
        val local = LocalDateTime.parse(raw.removeSuffix("Z"), DATE_TIME_FORMAT)
        val onSiteClock = if (raw.endsWith('Z')) local.atOffset(ZoneOffset.UTC).atZoneSameInstant(BERLIN).toLocalDateTime() else local
        return SiteTime(onSiteClock.toLocalDate(), onSiteClock.toLocalTime())
    }

    private data class SiteTime(
        val date: LocalDate,
        val time: LocalTime?
    ) : Comparable<SiteTime> {
        override fun compareTo(other: SiteTime): Int = compareValuesBy(this, other, { it.date }, { it.time })
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        val PRIVATE_HIRE = Regex("""^geschlossene\s+(?:veranstaltung|gesellschaft)""", RegexOption.IGNORE_CASE)
    }
}
