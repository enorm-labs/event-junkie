package de.norm.events.scraper.showfenster

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.billsChildrensShow
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.hasSoldOutMarker
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.mapWixEventStatus
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.stripSoldOutMarker
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode

/**
 * Parser for the Showfenster Theater calendar (`/übersichtskalender`), a Wix Events widget read from its warmup JSON.
 *
 * The payload names no category, but every event sold elsewhere links its Eventfrog page, whose path is the venue's own
 * filing (`/p/konzerte/jazz-blues/…`, `/p/theater-buehne/comedy-kabarett/…`). That path gives the type and a concert's
 * genre. Children's shows (`kinderveranstaltungen`, EVENT_SCOPE.md §3.5) and the swing course (`kurse-seminare`, §3.2)
 * are dropped. A cancellation or a sold-out night is a note in the title, read and then cut from it.
 */
class ShowfensterOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = WixEventsWarmupData.events(document, EventSource.SHOWFENSTER) ?: return emptyList()
        val inScope = events.filterNot(::isOutOfScope)
        logger.info { "Found ${events.size()} Showfenster event(s), ${inScope.size} in scope" }
        return inScope.mapSkippingFailures(logger, "Showfenster event") { parseEvent(it, baseUrl) }
    }

    private fun parseEvent(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val id = node.stringOrNull("id")
        val slug = node.stringOrNull("slug")
        val rawTitle = node.stringOrNull("title")
        if (id == null || slug == null || rawTitle == null) {
            logger.warn { "Showfenster event without an id, a slug or a title, skipping" }
            return null
        }
        val ticketUrl = ticketUrlOf(node)
        val category = eventfrogCategory(ticketUrl)
        val cancellation = CANCELLATION_NOTE.find(rawTitle)
        val title = stripSoldOutMarker(rawTitle.removeRange(cancellation?.range ?: IntRange.EMPTY)).replace(WHITESPACE_RUN, " ")
        val eventType = eventTypeOf(category, title)
        val description = node.stringOrNull("description")
        val schedule = parseWixSchedule(node.path("scheduling").path("config"))
        return ScrapedEvent(
            title = title,
            description = description,
            eventType = eventType.name,
            eventDate = schedule.date ?: UNRESOLVED_EVENT_DATE,
            startTime = schedule.startTime,
            endDate = schedule.endDate,
            endTime = schedule.endTime,
            imageUrl = node.path("mainImage").stringOrNull("url"),
            sourceUrl = resolveUrl(baseUrl, "/event-details/$slug"),
            // The slug follows the title, a cancellation note included, so the id is the stable key.
            sourceId = "${EventSource.SHOWFENSTER.sourceIdPrefix}$id",
            ticketUrl = ticketUrl,
            genre = CONCERT_GENRES[category?.sub]?.takeIf { eventType == EventType.CONCERT },
            soldOut = hasSoldOutMarker(rawTitle),
            // The phrase only: the description is prose, where a bare "frei" is "frei nach dem Märchen".
            free = hasFreeEntryPhrase(description),
            status = statusOf(node, cancellation),
            statusNote = cancellation?.value?.trim(' ', '-', '–', '(', ')'),
            artists = actsOf(title, eventType)
        )
    }

    private fun isOutOfScope(node: JsonNode): Boolean {
        val main = eventfrogCategory(ticketUrlOf(node))?.main
        return main in OUT_OF_SCOPE_CATEGORIES ||
            billsChildrensShow(node.stringOrNull("title").orEmpty(), node.stringOrNull("description"))
    }

    private fun ticketUrlOf(node: JsonNode): String? = node.path("registration").path("external").stringOrNull("registration")

    private fun statusOf(
        node: JsonNode,
        cancellation: MatchResult?
    ): String {
        val wixStatus = mapWixEventStatus(node.path("status"))
        return if (wixStatus == EventStatus.SCHEDULED.name && cancellation != null) parseEventStatus(cancellation.value) else wixStatus
    }

    /** A concert or comedy title names its act before ` - `; the house's formats (`Die Showfenster - Varieté Show`) name none. */
    private fun actsOf(
        title: String,
        eventType: EventType
    ): List<ScrapedArtist> {
        val head = title.substringBefore(" - ", missingDelimiterValue = "").trim()
        val named = eventType in PERFORMER_LED && head.isNotEmpty() && !HOUSE_FORMAT.containsMatchIn(head)
        return if (named) headlinersFromTitle(head) else emptyList()
    }
}

/** The two segments of an Eventfrog event path, `/p/<main>/<sub>/<event>`. */
private data class EventfrogCategory(
    val main: String,
    val sub: String
)

private fun eventfrogCategory(ticketUrl: String?): EventfrogCategory? =
    ticketUrl?.let(EVENTFROG_PATH::find)?.let { EventfrogCategory(it.groupValues[1], it.groupValues[2]) }

private fun eventTypeOf(
    category: EventfrogCategory?,
    title: String
): EventType =
    when (category?.main) {
        "konzerte" -> EventType.CONCERT
        "theater-buehne" -> THEATRE_TYPES[category.sub] ?: EventType.SHOW
        "musicals-shows" -> EventType.SHOW
        "kunst-ausstellungen" -> EventType.EXHIBITION
        else -> titleType(title)
    }

/** The quiz files under `freizeit-ausfluege`, and the open stage and the show with food link no Eventfrog page. */
private fun titleType(title: String): EventType =
    when {
        QUIZ_WORD.containsMatchIn(title) -> EventType.QUIZ
        SHOW_WORD.containsMatchIn(title) -> EventType.SHOW
        else -> EventType.OTHER
    }

private val EVENTFROG_PATH = Regex("""eventfrog\.[a-z]+/[a-z]{2}/p/([^/]+)/([^/]+)/""")

/** Children's shows (EVENT_SCOPE.md §3.5) and the swing course, a participation format (§3.2). */
private val OUT_OF_SCOPE_CATEGORIES = setOf("kinderveranstaltungen", "kurse-seminare")

private val THEATRE_TYPES = mapOf("comedy-kabarett" to EventType.COMEDY, "lesung" to EventType.READING)

private val CONCERT_GENRES = mapOf("jazz-blues" to "Jazz, Blues", "pop-rock" to "Pop, Rock", "folk" to "Folk")

private val PERFORMER_LED = setOf(EventType.CONCERT, EventType.COMEDY)

private val HOUSE_FORMAT = Regex("""show|quiz|theater""", RegexOption.IGNORE_CASE)

private val QUIZ_WORD = Regex("""quiz""", RegexOption.IGNORE_CASE)

private val SHOW_WORD = Regex("""\bshow\b""", RegexOption.IGNORE_CASE)

/** `(fällt aus, nächster Termin am 23.10.)`, `- (fällt leider aus)`, `- fällt leider aus wegen …`, always at the end. */
private val CANCELLATION_NOTE = Regex("""\s*[-–]?\s*\(?\s*f(?:ä|ae)llt\s+(?:leider\s+)?aus\b[^()]*\)?\s*$""", RegexOption.IGNORE_CASE)

private val WHITESPACE_RUN = Regex("""\s+""")
