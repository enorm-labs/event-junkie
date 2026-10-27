package de.norm.events.scraper.weltwirtschaft

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferVenueFormatType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.parseTecPage
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.tecDateTime
import de.norm.events.scraper.tecDescription
import de.norm.events.scraper.tecImageUrl
import de.norm.events.scraper.tecSourceId
import de.norm.events.scraper.tecTermNames
import de.norm.events.scraper.tecText
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Pure JSON parser for Weltwirtschaft's **The Events Calendar** REST API; the page shape and the readers are [parseTecPage]'s.
 *
 * - **Most entries are notices, not events**, and are skipped: anything under `Mitteilungen`; a title or slug that closes the house
 *   (`geschlossen`, `Exklusiv`, `Winterpause`, `früher Schluss`); a description that does; a run over several days (a seasonal menu,
 *   a screening season); and an all-day entry that names no clock, which is a catering day, not a night.
 * - **A brunch is out of scope** (EVENT_SCOPE.md §3.2), as at Alte Münze: `Christmas Brunch Around the World`.
 * - **A `<br>` splits the title** into title and subtitle. An all-day DJ night prints its start there (`ab 22:00`).
 * - **The DJs stand in the title** (`DJs Snow & Soundstream`, `DJ Set | Mo Lateef`). The resident is named only in the prose.
 * - **The image and the ticket link sit in the description**: the featured image is empty on every event.
 */
class WeltwirtschaftApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.WELTWIRTSCHAFT, ::toScrapedEvent)

    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val slug = event.stringOrNull("slug") ?: return null
        val start = event.tecDateTime("start_date") ?: return null
        val rawTitle = event.tecText("title") ?: return null
        val descriptionHtml = event.path("description").asString("")
        val allDay = event.path("all_day").asBoolean(false)
        val end = event.tecDateTime("end_date")
        val cleanedLines = rawTitle.split(LINE_BREAK).map(::cleanEventTitle)
        val lines = cleanedLines.mapNotNull { it.text.blankToNull() }
        val title = lines.firstOrNull() ?: return null
        val subtitle = lines.drop(1).joinToString(" – ").blankToNull()
        val description = event.tecDescription()
        val clock = if (allDay) labelledClock(lines.joinToString(" "), FROM) ?: labelledClock(description, FROM) else start.toLocalTime()
        (closure(event, slug, rawTitle, description) ?: notANight(start, end, allDay, clock))?.let { reason ->
            logger.debug { "Skipping Weltwirtschaft entry '$slug': $reason" }
            return null
        }
        val eventType = inferVenueFormatType(title, subtitle, VENUE_FORMATS)
        val until = end?.takeIf { !allDay && it.isAfter(start) && it.isBefore(start.plusHours(MAX_NIGHT_HOURS)) }
        val body = Jsoup.parseBodyFragment(descriptionHtml, event.path("url").asString(""))

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = description,
            eventType = eventType,
            typeIsFallback = eventType == EventType.OTHER.name,
            eventDate = start.toLocalDate(),
            startTime = clock,
            endDate = until?.toLocalDate(),
            endTime = until?.toLocalTime(),
            imageUrl = event.tecImageUrl() ?: body.selectFirst("img[src]")?.let(::largestSource),
            sourceUrl = event.stringOrNull("url") ?: return null,
            sourceId = event.tecSourceId(EventSource.WELTWIRTSCHAFT, slug),
            ticketUrl =
                event.stringOrNull("website") ?: body
                    .select("a[href]")
                    .firstOrNull(::isTicketLink)
                    ?.absUrl("href")
                    ?.blankToNull(),
            soldOut = cleanedLines.any { it.soldOut },
            artists = djsOf(lines)
        )
    }

    /** Why the entry announces a closure or a private booking, or is a brunch (out of scope), or null for an event. */
    private fun closure(
        event: JsonNode,
        slug: String,
        title: String,
        description: String?
    ): String? =
        when {
            event.tecTermNames("categories").any { it.equals(NOTICE_CATEGORY, ignoreCase = true) } -> "filed under $NOTICE_CATEGORY"
            NOTICE_WORDS.containsMatchIn(title) || NOTICE_WORDS.containsMatchIn(slug) -> "the title announces a closure"
            CLOSED_HOUSE.containsMatchIn(description.orEmpty()) -> "the description announces a closure"
            BRUNCH.containsMatchIn(title) -> "a brunch is out of scope"
            else -> null
        }

    /** Why the entry is a run of days or a day without a clock rather than a night, or null for a night. */
    private fun notANight(
        start: LocalDateTime,
        end: LocalDateTime?,
        allDay: Boolean,
        clock: LocalTime?
    ): String? {
        val days = end?.let { ChronoUnit.DAYS.between(start.toLocalDate(), it.toLocalDate()) } ?: 0
        return when {
            days > 1 || (allDay && days > 0) -> "a run over $days days"
            clock == null -> "an all-day entry with no clock"
            else -> null
        }
    }

    /** The DJs the title lines name after a `DJ`, `DJs` or `DJane` marker, or in the segment after a bare `DJ Set`. */
    private fun djsOf(lines: List<String>): List<ScrapedArtist> {
        val segments = lines.flatMap { it.split(SEGMENT_SEPARATOR) }.map { it.trim() }
        val named =
            segments.flatMapIndexed { index, segment ->
                when {
                    DJ_SET_LABEL.matches(segment) -> {
                        listOfNotNull(segments.getOrNull(index + 1))
                    }

                    else -> {
                        listOfNotNull(
                            DJ_MARKER
                                .find(segment)
                                ?.groupValues
                                ?.get(1)
                                ?.takeUnless { DJ_SET_LABEL.matches("DJ $it") }
                        )
                    }
                }
            }
        return named
            .flatMap(::splitSupportActs)
            .map { stripArtistSuffix(it.replace(ORIGIN_NOTE, "")) }
            .filterNot(::isNonArtistName)
            .distinct()
            .map { ScrapedArtist(name = it, role = ArtistRole.DJ) }
    }

    /** The widest candidate of an image's `srcset`, or its `src`: the description embeds a 300-pixel thumbnail. */
    private fun largestSource(image: Element): String? =
        image
            .attr("srcset")
            .split(',')
            .mapNotNull { SRCSET_CANDIDATE.find(it.trim())?.destructured }
            .maxByOrNull { (_, width) -> width.toInt() }
            ?.component1()
            ?: image.absUrl("src").blankToNull()

    private fun isTicketLink(link: Element): Boolean = TICKET.containsMatchIn(link.text()) || TICKET_HOST.containsMatchIn(link.attr("href"))

    private companion object {
        const val NOTICE_CATEGORY = "Mitteilungen"

        /** A closure, a private booking or changed hours, in a title or a slug. */
        val NOTICE_WORDS =
            Regex(
                """geschlossen|exklusiv|pause|staatsbesuch|(?:ö|oe)ffnungszeiten|fr(?:ü|ue)her.schluss|offene stellen|l(?:ä|ae)nger.auf""",
                RegexOption.IGNORE_CASE
            )

        val BRUNCH = Regex("""brunch""", RegexOption.IGNORE_CASE)

        /** A description that closes the restaurant or books it out: `bleibt … geschlossen`, `eine Exklusiv-Veranstaltung`. */
        val CLOSED_HOUSE = Regex("""geschlossen|exklusiv""", RegexOption.IGNORE_CASE)

        val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

        /** The label before an all-day night's start: `ab 22:00`, `ab 22 Uhr`. */
        const val FROM = "ab"

        /** Longer than a night: the API's end is then a placeholder (`22:00` to `22:00` the next day). */
        const val MAX_NIGHT_HOURS = 12L

        val SEGMENT_SEPARATOR = Regex("""\s+(?:\||//|·)\s+""")

        val DJ_SET_LABEL = Regex("""DJ[- ]Set""", RegexOption.IGNORE_CASE)

        /** `DJ`, `DJs` or `DJane`, then a capitalised name up to an `ab 22:00` or a `legt … auf`. */
        val DJ_MARKER = Regex("""\bDJ(?:s|ane)?\s+(\p{Lu}.*?)(?:\s+(?:ab|legt|legen)\s.*)?$""")

        /** A home city after a DJ: `Zapata (NY)`, `Disco Daddy (Berlin)`. */
        val ORIGIN_NOTE = Regex("""\s*\([^)]*\)""")

        val SRCSET_CANDIDATE = Regex("""^(\S+)\s+(\d+)w$""")

        val TICKET = Regex("""ticket""", RegexOption.IGNORE_CASE)

        val TICKET_HOST = Regex("""eventbrite|eventim|pretix|tickettailor""", RegexOption.IGNORE_CASE)

        /** The house's own series and the formats its titles name; checked before the shared title cues. */
        val VENUE_FORMATS: Map<String, String> =
            linkedMapOf(
                "dance the oyster" to EventType.PARTY.name,
                "disco" to EventType.PARTY.name,
                "dj" to EventType.PARTY.name,
                "live jazz" to EventType.CONCERT.name,
                "konzert" to EventType.CONCERT.name
            )
    }
}
