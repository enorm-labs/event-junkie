package de.norm.events.scraper.punchline

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.flightStringOrNull
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.jsonArrayAt
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.nextFlightPayload
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Pure parser for the PUNCH L!NE Club's ticket page (`/de/tickets`), a Next.js App Router page whose
 * flight payload carries the whole date list as `formattedShowDates`: per date an ISO instant, the
 * show's name, its acts (`artistNames`), its text, a note that can name the doors (`Einlass: 19:00
 * Uhr`) and the show page. No format is given, so a show or act that names a band, an orchestra
 * or an unplugged set is a concert and the rest is comedy.
 *
 * A show's later dates refer to its first date's show-page link instead of repeating it
 * (`"$1f:props:formattedShowDates:0:linkDetailpage"`); the reference is resolved by its index.
 */
class PunchlineTicketsPageScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper = JsonMapper.builder().build()

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val dates = jsonArrayAt(nextFlightPayload(document), DATES_KEY)?.let { jsonMapper.readTree(it).toList() }.orEmpty()
        logger.info { "Found ${dates.size} PUNCH L!NE show date(s) on $baseUrl" }

        return dates.mapSkippingFailures(logger, "PUNCH L!NE show date") { date ->
            toScrapedEvent(date, dates, baseUrl)
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required fields are clearer than nesting
    private fun toScrapedEvent(
        date: JsonNode,
        dates: List<JsonNode>,
        baseUrl: String
    ): ScrapedEvent? {
        val show = date.flightStringOrNull("showName") ?: return null
        val start =
            date.flightStringOrNull("isoDate")?.let {
                try {
                    Instant.parse(it).atZone(BERLIN)
                } catch (_: DateTimeParseException) {
                    null
                }
            } ?: return null
        val path = showPath(date, dates) ?: return null
        val acts = date.flightStringOrNull("artistNames")
        return ScrapedEvent(
            title = show,
            subtitle = acts,
            description = date.flightStringOrNull("showDescription"),
            eventType = if (MUSIC.containsMatchIn("$show ${acts.orEmpty()}")) EventType.CONCERT.name else EventType.COMEDY.name,
            eventDate = start.toLocalDate(),
            doorsTime = date.flightStringOrNull("description")?.let { DOORS.find(it) }?.let { parseTime(it.groupValues[1]) },
            startTime = start.toLocalTime(),
            sourceUrl = URI(baseUrl).resolve("/de$path").toString(),
            sourceId = "${EventSource.PUNCHLINE.sourceIdPrefix}${path.substringAfterLast('/')}-${start.toLocalDate()}-${start.hour}",
            artists = acts?.let { headlinersFromTitle(it) }.orEmpty()
        )
    }

    /** The show page's path, following a `$<row>:props:formattedShowDates:<n>:linkDetailpage` reference to date n. */
    private fun showPath(
        date: JsonNode,
        dates: List<JsonNode>
    ): String? {
        val link = date.path("linkDetailpage")
        val target =
            if (link.isString) {
                LINK_REFERENCE
                    .find(link.asString(""))
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?.let { dates.getOrNull(it)?.path("linkDetailpage") }
            } else {
                link
            }
        return target?.flightStringOrNull("url")?.takeIf { it.startsWith("/") }
    }

    private companion object {
        const val DATES_KEY = "formattedShowDates"
        val LINK_REFERENCE = Regex("""formattedShowDates:(\d+):linkDetailpage$""")

        /** The words that mark one of the concerts the club books between its comedy dates. */
        val MUSIC = Regex("""\b(?:unplugged|band|orchestra|orchester|konzert|concert)\b""", RegexOption.IGNORE_CASE)
        val DOORS = Regex("""Einlass:?\s*(\d{1,2}:\d{2})""")
    }
}
