package de.norm.events.scraper.radar

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.mapSkippingFailures
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

// Shared reader for radar.squat.net's search API, `api/1.2/search/events.json?facets[group][]=<nid>`
// (#2166). Squat and DIY venues post their own programme there as a radar group; each group is one
// source. Stressfaktor is a front end over the same data. Both sites' HTML sits behind an Anubis
// proof-of-work wall, which is the operator's refusal of automated clients; the API is outside it.

private val logger = KotlinLogging.logger {}

/** The categories that make a row programme: the rest is bars, food, film, meetings and protest. */
private val PROGRAMME_CATEGORIES = setOf("music/concert", "party")

/** An open-decks night ("Open Breakbeat Decks"), which radar can file under concert: a DJ party, not a band. */
private val OPEN_DECKS = Regex("""\b(?:open|offene)[\s-]+(?:\p{L}+\s+)?decks?\b""", RegexOption.IGNORE_CASE)

private val jsonMapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

/** One programme row of a radar group, in the venue's local time. */
data class RadarEvent(
    /** The Drupal node id, the row's key in `result`. Stable, and unique per occurrence; the `url` can be a path alias. */
    val nodeId: Long,
    val title: String,
    val start: ZonedDateTime,
    /** `null` where radar repeats the start as the end, which is how it says "no end". */
    val end: ZonedDateTime?,
    val description: String?,
    /** The node's page, the back-link. */
    val url: String,
    /** Free text where set (`7-78€`), never a structured price. */
    val price: String?,
    val categories: List<String>
) {
    /** `CONCERT` where radar names a concert, but `PARTY` for a party-only row or an open-decks night. */
    val eventType: String
        get() =
            if ("music/concert" in categories && !OPEN_DECKS.containsMatchIn("$title ${description.orEmpty()}")) {
                EventType.CONCERT.name
            } else {
                EventType.PARTY.name
            }
}

/** The rows of one response, and the `count` radar reports, which exceeds the rows when a page is cut short. */
data class RadarListing(
    val events: List<RadarEvent>,
    val rowsRead: Int,
    val count: Int
)

/**
 * Parses a search response into its programme rows: kept when a category is `music/concert` or
 * `party`, and with repeats dropped.
 *
 * **A row can repeat.** The same concert came back three times, one title, start and body under
 * three node ids at KØPI. One per start and title is kept, the lowest node id, so the
 * `sourceId` does not move between runs.
 *
 * A malformed row is skipped with a warning; an unparseable body gives an empty listing.
 */
@Suppress("TooGenericExceptionCaught") // A malformed payload or row must degrade, never abort the import.
internal fun parseRadarEvents(
    json: String,
    venue: String
): RadarListing {
    val root =
        try {
            jsonMapper.readTree(json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse the $venue radar response" }
            return RadarListing(emptyList(), 0, 0)
        }
    val rows = root.path("result").let { if (it.isObject) it.properties().toList() else emptyList() }
    val events =
        rows
            .mapSkippingFailures(logger, "a $venue radar row") { (key, row) ->
                parseRow(key.toLongOrNull(), row, venue)
            }.filter { event -> event.categories.any { it in PROGRAMME_CATEGORIES } }
            .sortedBy { it.nodeId }
            .distinctBy { it.start to it.title.lowercase() }
            .sortedBy { it.start }
    return RadarListing(events, rowsRead = rows.size, count = root.path("count").asInt(rows.size))
}

private fun parseRow(
    nodeId: Long?,
    row: JsonNode,
    venue: String
): RadarEvent? {
    val url = row.path("url").asString("").blankToNull()
    if (url == null || nodeId == null) {
        logger.warn { "$venue radar row has no node url, skipping" }
        return null
    }
    val title = row.path("title").asString("").blankToNull()
    if (title == null) {
        logger.warn { "$venue radar row $nodeId has no title, skipping" }
        return null
    }
    val dateTime = row.path("date_time").firstOrNull()
    val start = dateTime?.path("time_start")?.asString("").toBerlinTime()
    if (start == null) {
        logger.warn { "$venue radar row $nodeId has no parseable start, skipping" }
        return null
    }
    return RadarEvent(
        nodeId = nodeId,
        title = title,
        start = start,
        end =
            dateTime
                ?.path("time_end")
                ?.asString("")
                .toBerlinTime()
                ?.takeIf { it.isAfter(start) },
        description = htmlParagraphText(row.path("body").path("value").asString("")),
        url = url,
        price =
            row
                .path("price")
                .takeIf { it.isString }
                ?.asString()
                .blankToNull(),
        categories = row.path("category").mapNotNull { it.path("name").asString("").blankToNull() }
    )
}

private fun String?.toBerlinTime(): ZonedDateTime? =
    this.blankToNull()?.let {
        try {
            OffsetDateTime.parse(it).atZoneSameInstant(BERLIN)
        } catch (_: DateTimeParseException) {
            null
        }
    }
