package de.norm.events.scraper.claerchens

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.billsChildrensShow
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseClockPrefix
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTitleStatus
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.format.DateTimeFormatter

/**
 * Pure parser for the Pie Calendar data on Clärchens Ballhaus's `/programm/` page: the FullCalendar
 * `eventSources` literal, one entry per occurrence with an HTML entity title, a local ISO start, the
 * event's permalink and its text as HTML. A weekly night repeats its permalink, so the identity is
 * the permalink's slug plus the start. The `end` field is a placeholder and is not read.
 */
class ClaerchensOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper = JsonMapper.builder().build()

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val entries = calendarEntries(document)
        if (entries == null) {
            logger.warn { "No calendar data on the Clärchens Ballhaus programme page $baseUrl" }
            return emptyList()
        }
        logger.info { "Found ${entries.size} calendar entries on the Clärchens Ballhaus programme page" }
        return entries
            .mapSkippingFailures(logger, "Clärchens Ballhaus calendar entry") { toScrapedEvent(it) }
            .distinctBy { it.sourceId }
    }

    /** Every occurrence of the `eventSources: [[…], …]` literal in the calendar's inline script, flattened. */
    private fun calendarEntries(document: Document): List<JsonNode>? {
        val script = document.select("script").map { it.data() }.firstOrNull { EVENT_SOURCES_KEY in it } ?: return null
        val literal = script.substring(script.indexOf(EVENT_SOURCES_KEY) + EVENT_SOURCES_KEY.length - 1)
        val sources = jsonMapper.createParser(literal).use { it.readValueAsTree<JsonNode>() }
        return sources.flatMap { source -> source.toList() }
    }

    private fun toScrapedEvent(node: JsonNode): ScrapedEvent? {
        val title = node.stringOrNull("title")?.let(::decodeHtmlEntities)?.blankToNull()
        val start = node.stringOrNull("start")
        val date = start?.let(::parseIsoDate)
        val permalink = node.stringOrNull("permalink")
        if (title == null || date == null || permalink == null) {
            logger.warn { "Clärchens Ballhaus entry without a title, a start or a permalink ('$title', $start, $permalink), skipping" }
            return null
        }
        val slug = extractEventSlug(permalink, prefix = "/")
        val description = htmlParagraphText(node.path("details").asString(""))
        if (!isProgramme(title, slug, description)) {
            logger.debug { "Clärchens Ballhaus entry '$title' on $date is not an evening programme, skipping" }
            return null
        }
        val startTime = parseClockPrefix(start.substringAfter('T', ""))
        val eventType =
            if (DANCE_NIGHT.containsMatchIn("$title $slug ${description.orEmpty()}")) {
                EventType.PARTY.name
            } else {
                inferConcertVenueType(title, subtitle = null, venueKeywords = TITLE_TYPES)
            }
        return ScrapedEvent(
            title = title,
            description = description,
            eventType = eventType,
            eventDate = date,
            doorsTime = labelledClock(description, "Einlass"),
            startTime = startTime,
            imageUrl =
                node
                    .path("featuredimage")
                    .takeIf { it.isString }
                    ?.asString()
                    ?.blankToNull(),
            sourceUrl = permalink,
            sourceId = "${EventSource.CLAERCHENS_BALLHAUS.sourceIdPrefix}$slug-$date${startTime?.let { "-${it.format(CLOCK)}" }.orEmpty()}",
            priceBoxOffice = euroAmounts(description.orEmpty()).minOrNull(),
            free = detectFree(title = title),
            status = parseTitleStatus(title) ?: EventStatus.SCHEDULED.name,
            artists = actOf(title, eventType)
        )
    }

    /** `Donata – Konzert`: the one billing form that names an act; the series titles name none. */
    private fun actOf(
        title: String,
        eventType: String
    ): List<ScrapedArtist> =
        ACT_CONCERT
            .find(title)
            ?.takeIf { eventType == EventType.CONCERT.name }
            ?.let { listOf(ScrapedArtist(name = it.groupValues[1].trim(), role = "HEADLINER")) }
            .orEmpty()

    private companion object {
        const val EVENT_SOURCES_KEY = "eventSources: ["
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")
        val ACT_CONCERT = Regex("""^(.+?)\s+[–-]\s+Konzert$""")

        /** The comedy slam, which the shared title cues do not type; a bare `comedy` names a band elsewhere. */
        val TITLE_TYPES = mapOf("comedy" to EventType.COMEDY.name)
    }
}

/**
 * Whether a calendar entry belongs to the evening programme (EVENT_SCOPE.md §3.2 and §3.5). A dance
 * course, a workshop, the house tour, a sing-along choir and the wine dinner are taken part in, and
 * a `Kinder…` or `Familien…` concert is for children. A weekly night that opens with a beginners'
 * course and goes on as a `Tanzabend` or a `Milonga` stays: the course is its first hour.
 */
internal fun isProgramme(
    title: String,
    slug: String,
    description: String?
): Boolean {
    val text = "$title $slug ${description.orEmpty()}"
    val participation = PARTICIPATION_FORMAT.containsMatchIn(text) && !DANCE_NIGHT.containsMatchIn(text)
    return !participation && !billsChildrensShow(title, null)
}

/** The page's words for a format one takes part in; the house tour shows it in its slug, `tour-…`. */
private val PARTICIPATION_FORMAT =
    Regex("""workshop|tanzkurs|anfängerkurs|führung|(?<!\p{L})tour(?!\p{L})|werdet teil|menü""", RegexOption.IGNORE_CASE)

/** A social dance night, on its own or after a course; `Standard Latein` names it only in its slug, `…-tanzabend`. */
private val DANCE_NIGHT = Regex("""tanzabend|milonga|(?<!\p{L})(?:party|ball)(?!\p{L})""", RegexOption.IGNORE_CASE)
