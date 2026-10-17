package de.norm.events.scraper.zitaclub

import de.norm.events.event.EventType
import de.norm.events.scraper.CalendarProperty
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.calendarEvents
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.siteTime
import io.github.oshai.kotlinlogging.KotlinLogging

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
        val start = entry["DTSTART"]?.siteTime()
        if (title == null || url == null || start == null) {
            logger.warn { "Zita Club calendar entry '$title' lacks a title, URL or start, skipping" }
            return null
        }
        if (PRIVATE_HIRE.containsMatchIn(title)) return null
        val end = entry["DTEND"]?.siteTime()?.takeIf { it > start }
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

    private companion object {
        val PRIVATE_HIRE = Regex("""^geschlossene\s+(?:veranstaltung|gesellschaft)""", RegexOption.IGNORE_CASE)
    }
}
