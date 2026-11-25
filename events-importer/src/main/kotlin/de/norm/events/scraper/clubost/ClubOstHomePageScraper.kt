package de.norm.events.scraper.clubost

import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.SCRAPER_JSON
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.endOn
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.readTreeOrNull
import de.norm.events.scraper.splitBackToBack
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure parser for Club OST's homepage, which carries the whole programme as a JSON array in `script#events-data`; the
 * visible programme is drawn from it in the browser.
 *
 * - **`id` is the booking system's UUID**, the key of the `/event/<uuid>/` pages the site had before. Those pages are gone
 *   and redirect to [PROGRAMME_FRAGMENT], so every event links there.
 * - **`presstext` is the Resident Advisor link**, which the site shows as the ticket button.
 * - **`set_start_day` and `set_end_day` count days from `date`**, so a set after midnight needs no inference.
 * - `end_date` is set only where a night runs past the next day, as New Year's Eve does.
 * - The club programmes techno nights, so every event is [EventType.PARTY] and every act a DJ.
 */
class ClubOstHomePageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val json = document.selectFirst(EVENTS_DATA)?.data()
        if (json.isNullOrBlank()) {
            logger.warn { "Club OST page has no events-data block" }
            return emptyList()
        }
        val rows = SCRAPER_JSON.readTreeOrNull(json, logger) { "Unreadable events-data block on the Club OST page" } ?: return emptyList()
        if (!rows.isArray) {
            logger.warn { "Club OST events-data is not an array" }
            return emptyList()
        }
        return rows.toList().mapSkippingFailures(logger, "Club OST event") { toEvent(it, sourceUrl) }
    }

    private fun toEvent(
        row: JsonNode,
        sourceUrl: String
    ): ScrapedEvent? {
        val id = row.stringOrNull("id")
        val (heading, doorNote) = splitDoorNote(row.stringOrNull("title").orEmpty())
        val title = cleanEventTitle(heading)
        val eventDate = row.stringOrNull("date")?.let(::parseIsoDate)
        if (id == null || title.text.isBlank() || eventDate == null) {
            logger.warn { "Club OST event '$id' has no id, title or date, skipping" }
            return null
        }

        val startTime = parseTime(row.stringOrNull("start_time"))
        val endTime = parseTime(row.stringOrNull("end_time"))
        val bookings = row.path("bookings").toList()
        val room = onlyFloor(bookings)
        return ScrapedEvent(
            title = title.text,
            soldOut = title.soldOut,
            priceNote = doorNote,
            eventDate = eventDate,
            startTime = startTime,
            endDate = endTime?.let { row.stringOrNull("end_date")?.let(::parseIsoDate) ?: endOn(eventDate, startTime, it) },
            endTime = endTime,
            eventType = EventType.PARTY.name,
            room = room,
            imageUrl = row.path("attachments").path(0).stringOrNull("url"),
            sourceUrl = sourceUrl.substringBefore('#') + PROGRAMME_FRAGMENT,
            sourceId = "${EventSource.CLUB_OST.sourceIdPrefix}$id",
            ticketUrl = row.stringOrNull("presstext")?.takeIf { it.startsWith("https://") },
            artists = bookings.flatMap { toActs(it, eventDate, room) }
        )
    }

    /** The floor every booking is on, stored as the night's room. Null when the bookings differ or one names none. */
    private fun onlyFloor(bookings: List<JsonNode>): String? = bookings.map { it.stringOrNull("floor") }.distinct().singleOrNull()

    private fun toActs(
        booking: JsonNode,
        eventDate: LocalDate,
        room: String?
    ): List<ScrapedArtist> {
        val name = booking.stringOrNull("artist_name")?.takeUnless { it.equals(INTRO, ignoreCase = true) } ?: return emptyList()
        return splitBackToBack(
            ScrapedArtist(
                name = name,
                role = ArtistRole.DJ,
                stage = booking.stringOrNull("floor").takeIf { room == null },
                setStart = instantOf(booking, "set_start", eventDate),
                setEnd = instantOf(booking, "set_end", eventDate)
            )
        )
    }

    /** The Berlin instant of [field] and its `_day` offset from [eventDate]. */
    private fun instantOf(
        booking: JsonNode,
        field: String,
        eventDate: LocalDate
    ): Instant? =
        parseTime(booking.stringOrNull(field))?.let { time: LocalTime ->
            val day = eventDate.plusDays(booking.path("${field}_day").asLong(0))
            day.atTime(time).atZone(BERLIN).toInstant()
        }

    private companion object {
        private const val EVENTS_DATA = "script#events-data"
        private const val PROGRAMME_FRAGMENT = "#programme"

        /** The opening slot of a night, billed like an act (`Mattermind`, 23:00 to 23:30). */
        private const val INTRO = "Intro"
    }
}

/**
 * [title] split from the door note the venue prints in front of it in square brackets:
 * `[FREE ENTRY TILL 00:30] SAVORY` is the night `SAVORY` and the note `FREE ENTRY TILL 00:30`. The note is
 * an entry condition, kept as the price note. Unchanged when nothing follows the brackets.
 */
internal fun splitDoorNote(title: String): Pair<String, String?> {
    val match = DOOR_NOTE.find(title) ?: return title to null
    val rest = title.substring(match.range.last + 1).trim()
    return if (rest.isBlank()) title to null else rest to match.groupValues[1].trim()
}

private val DOOR_NOTE = Regex("""^\s*\[([^\]]+)]\s*""")
