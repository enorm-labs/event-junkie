package de.norm.events.scraper.mbia

import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.SCRAPER_JSON
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.labelledClockPattern
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * Parser for M-BIA's `/veranstaltungen` page, read from the Wix Data collection `Events` in the
 * `wix-warmup-data` script, as DSTRKT's. The page loads the whole collection, past nights included.
 * A record holds the night's name, a UTC `dateStart`, its styles, one rich-text lineup per floor,
 * a description, a flyer and a ticket link. The record's `_id` is the night's identity.
 *
 * Every `dateStart` carries 22:00, New Year's Eve 21:00, and no page prints it, so only the date
 * is kept. A few descriptions state the doors on a line of their own ([mbiaDoorsTime]). A lineup
 * is one paragraph per act, opened by a style line (`Hardtechno • Schranz`).
 */
class MbiaOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val records = records(document) ?: return emptyList()
        logger.info { "Found ${records.size()} record(s) in the M-BIA $COLLECTION collection" }
        return records.mapSkippingFailures(logger, "M-BIA event record") { parseRecord(it, baseUrl) }
    }

    private fun records(document: Document): JsonNode? {
        val script = document.getElementById("wix-warmup-data")
        if (script == null) {
            logger.warn { "No 'wix-warmup-data' script on the M-BIA events page" }
            return null
        }
        val records =
            runCatching { SCRAPER_JSON.readTree(script.data()) }
                .onFailure { logger.warn(it) { "Failed to parse the M-BIA wix-warmup-data JSON" } }
                .getOrNull()
                ?.path("appsWarmupData")
                ?.path("dataBinding")
                ?.path("dataStore")
                ?.path("recordsByCollectionId")
                ?.path(COLLECTION)
                ?.takeIf { it.isObject }
        if (records == null) logger.warn { "No $COLLECTION collection in the M-BIA warmup payload" }
        return records
    }

    private fun parseRecord(
        record: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val id = record.stringOrNull("_id") ?: return null.also { logger.warn { "M-BIA record has no _id, skipping" } }
        val cleanedTitle =
            record
                .stringOrNull("title_fld")
                ?.trim()
                ?.blankToNull()
                ?.let { cleanEventTitle(it) }
        val title = cleanedTitle?.text
        if (title.isNullOrBlank()) {
            logger.warn { "M-BIA record '$id' has no title, skipping" }
            return null
        }
        val date = record.path("dateStart").stringOrNull("\$date")?.let { parseDate(it) }
        if (date == null) {
            logger.warn { "M-BIA record '$id' has no parseable date, skipping" }
            return null
        }
        val path = record.stringOrNull("link-events-1-title_fld")?.takeIf { it.startsWith("/") }
        val description = htmlParagraphText(record.path("descriptionLong").asString(""))
        return ScrapedEvent(
            title = title,
            soldOut = cleanedTitle.soldOut,
            description = description,
            eventType = EventType.PARTY.name,
            eventDate = date,
            doorsTime = mbiaDoorsTime(description),
            imageUrl = record.stringOrNull("image_fld")?.let(::imageUrl),
            sourceUrl = path?.let { "$SITE_URL$it" } ?: baseUrl,
            sourceId = "${EventSource.M_BIA.sourceIdPrefix}$id",
            ticketUrl = record.stringOrNull("ticketUrl")?.trim()?.takeIf { url -> EVENT_TICKET.any { it.containsMatchIn(url) } },
            genre = mbiaGenre(record.path("musicStyle").mapNotNull { it.asString("").trim().blankToNull() }, title),
            artists = lineup(record).ifEmpty { actsFromTitle(title) }
        )
    }

    /** Both floors' acts. A floor is named only when both have acts; one floor is the whole night. */
    private fun lineup(record: JsonNode): List<ScrapedArtist> {
        val floors = FLOORS.map { (field, name) -> name to acts(record.path(field).asString("")) }.filter { it.second.isNotEmpty() }
        return floors
            .flatMap { (floor, acts) -> acts.map { (name, role) -> ScrapedArtist(name = name, role = role, stage = floor.takeIf { floors.size > 1 }) } }
            .distinctBy { it.name.lowercase() }
    }

    /** One act per paragraph, without its emoji, flag or `from <city>`, and a trailing `LIVE` read as a live set. */
    private fun acts(html: String): List<Pair<String, ArtistRole>> =
        Jsoup
            .parseBodyFragment(html)
            .select("p")
            .map {
                it
                    .text()
                    .replace(' ', ' ')
                    .replace(EDGE_SYMBOLS, "")
                    .replace(ORIGIN, "")
                    .trim()
            }.filterNot { it.isBlank() || it.contains('•') || NOT_AN_ACT.matches(it) || isNonArtistName(it) }
            .map { line ->
                val live = LIVE_SUFFIX.find(line)
                if (live != null) line.substring(0, live.range.first).trim() to ArtistRole.LIVE else line to ArtistRole.DJ
            }.filter { it.first.isNotBlank() }

    private fun parseDate(text: String) =
        try {
            Instant.parse(text).atZone(BERLIN).toLocalDate()
        } catch (_: DateTimeParseException) {
            null
        }

    private companion object {
        const val COLLECTION = "Events"
        const val SITE_URL = "https://www.m-bia.de"

        /** The two lineup fields and the floor names the event page prints above them. */
        val FLOORS = listOf("lineup1" to "Arena Floor", "lineupSecondFloor" to "Bunker Floor")

        /** Ticket links that sell one night. An RA promoter page, an organiser page or Instagram sells none. */
        val EVENT_TICKET =
            listOf(
                Regex("""^https://(?:[a-z]{2}\.)?ra\.co/events/\d+"""),
                Regex("""^https://www\.eventbrite\.[a-z.]+/e/"""),
                Regex("""^https://[a-z0-9-]+\.ticket\.io/""")
            )

        /** Emoji and flags before a name, stars around a slot; a name may end in `.`, `!` or `)`. */
        val EDGE_SYMBOLS = Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N}.!)]+$""")

        /** `Diztorted from Belfast`: where the act comes from, not its name. */
        val ORIGIN = Regex("""\s+from\s+\p{Lu}\p{L}+$""")
        val LIVE_SUFFIX = Regex("""\s+live$""", RegexOption.IGNORE_CASE)

        /** A placeholder or a note on the floor, not an act. */
        val NOT_AN_ACT = Regex("""tba|tbc|all night long.*|contest winner""", RegexOption.IGNORE_CASE)
    }
}

/**
 * A Wix media reference (`wix:image://v1/<file>/<title>#…`) as its static URL. A record that holds
 * a plain `https` URL keeps it.
 */
internal fun imageUrl(reference: String): String? =
    when {
        reference.startsWith("https://") -> reference
        else -> WIX_IMAGE.find(reference)?.let { "https://static.wixstatic.com/media/${it.groupValues[1]}" }
    }

private val WIX_IMAGE = Regex("""^wix:image://v1/([^/#]+)""")

/**
 * The DJs a title names behind a marker, for a night whose lineup fields are empty:
 * `Rave Cage with Dr. Sheppat & NYXEA`, `Goanautika w./ Bliss`, `GOA Herbst Edition [pres. Naturalize, Vertigos]`,
 * `9Y TEKKNOLOGIE • D-FENCE • FABITEKK`. What stands before the marker is the night's name.
 */
internal fun actsFromTitle(title: String): List<ScrapedArtist> {
    val billing =
        PRESENTED_ACTS.find(title)?.groupValues?.get(1)
            ?: WITH_MARKER.find(title)?.let { title.substring(it.range.last + 1) }
            ?: title.split(BULLET).drop(1).joinToString(", ")
    return splitSupportActs(billing)
        .map { it.trim() }
        .filterNot { it.isBlank() || isNonArtistName(it) }
        .distinct()
        .map { ScrapedArtist(name = it, role = ArtistRole.DJ, titleDerived = true) }
}

/**
 * The record's styles, plus `Goa` when the title names a Goa night (`GOA MAGIC`, `Goanautika`):
 * the styles never list Goa, and the series names are the only place the club says it.
 */
internal fun mbiaGenre(
    styles: List<String>,
    title: String
): String? = (styles + listOfNotNull(GOA.takeIf { GOA_NIGHT.containsMatchIn(title) })).distinct().joinToString(", ").blankToNull()

private const val GOA = "Goa"

/**
 * The doors time of a description line that opens with its label (`🕚 Einlass: 23:00 Uhr`,
 * `Doors Open: 23:00`). A clock in prose (`📅 21.11. ab 23 Uhr`) is not read.
 */
internal fun mbiaDoorsTime(description: String?): LocalTime? =
    description?.lines()?.firstNotNullOfOrNull { line -> DOORS_LINE.find(line)?.let { labelledClock(it.value, DOORS_LABELS) } }

/** A doors label and its clock at the start of a line, after any emoji. */
private val DOORS_LINE = Regex("^[^\\p{L}\\p{N}]*" + labelledClockPattern(DOORS_LABELS).pattern, RegexOption.IGNORE_CASE)

/** `GOA` or `Goanautika` as a word of the title. */
private val GOA_NIGHT = Regex("""\bgoa(?:nautika)?\b""", RegexOption.IGNORE_CASE)

/** `[pres. <acts>]` in brackets. */
private val PRESENTED_ACTS = Regex("""\[\s*pres(?:ents)?\.?\s+([^\]]+)\]""", RegexOption.IGNORE_CASE)

/** `with`, `w/` or `w./` between the night's name and its acts. */
private val WITH_MARKER = Regex("""\s(?:with|w\.?/)\s+""", RegexOption.IGNORE_CASE)

/** The bullet the site writes between a night's name and each act. */
private val BULLET = Regex("""\s*•\s*""")
