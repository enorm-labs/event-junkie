package de.norm.events.scraper.tausend

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaName
import de.norm.events.scraper.schemaOffers
import de.norm.events.scraper.schemaStatus
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * Pure parser for Bar Tausend's Divi lineup page, `/lineup/`, which lists every announced night.
 *
 * The page carries a schema.org `MusicEvent` graph with full dates, image and Resident Advisor offer,
 * so the year-less `Do | 08.10. | 21 H` line is never read. The graph's `description` is cut at about
 * 300 characters, so the full text comes from each night's `.bt-lu-body` block, matched on the same
 * [sourceId]. The `endDate` is a fixed 3 am on every night and is not stored.
 */
class TausendOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val nights = document.jsonLdEvents()
        logger.info { "Found ${nights.size} night(s) in the Tausend lineup JSON-LD" }
        val texts = descriptions(document)
        return nights.mapSkippingFailures(logger, "Tausend night") { parseNight(it, texts, baseUrl) }
    }

    /** Each night's full text by `sourceId`, in whichever language the page is in. */
    fun descriptions(document: Document): Map<String, String?> =
        document
            .select(".bt-lu-body")
            .mapNotNull { body ->
                val start = body.selectFirst(".bt-lu-date time[datetime]")?.attr("datetime")?.let(::nightStart)
                val name = body.textAt(".bt-lu-name")
                if (start == null || name.isNullOrBlank()) return@mapNotNull null
                val text =
                    body
                        .select(".bt-lu-text p")
                        .map { it.text().trim() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                sourceId(start.date, name) to text.ifBlank { null }
            }.toMap()

    @Suppress("ReturnCount") // Guard clauses for the required name and date are clearer than nesting.
    private fun parseNight(
        node: JsonNode,
        texts: Map<String, String?>,
        baseUrl: String
    ): ScrapedEvent? {
        val (act, series) = splitName(node.schemaName() ?: return null)
        val start = node.stringOrNull("startDate")?.let(::nightStart)
        if (start == null) {
            logger.warn { "Tausend night '$act' has no parseable startDate, skipping" }
            return null
        }
        val id = sourceId(start.date, act)
        val eventType = if (series?.contains(LIVE_SERIES, ignoreCase = true) == true) EventType.CONCERT.name else EventType.PARTY.name
        return ScrapedEvent(
            title = act,
            subtitle = series?.takeUnless { it.equals(act, ignoreCase = true) },
            description = texts[id] ?: node.stringOrNull("description"),
            eventType = eventType,
            eventDate = start.date,
            startTime = start.time,
            imageUrl = node.schemaImageUrl(),
            sourceUrl = node.stringOrNull("url")?.takeIf { it.startsWith("http") } ?: baseUrl,
            sourceId = id,
            ticketUrl = node.schemaOffers().firstNotNullOfOrNull { offer -> offer.stringOrNull("url")?.takeIf { it.startsWith("http") } },
            status = node.schemaStatus(),
            artists = artists(act, series, eventType)
        )
    }

    /** `House Queens · Tausend Electronica` is the act, then the series; a series named twice has no act. */
    private fun splitName(name: String): Pair<String, String?> {
        val act = name.substringBefore(NAME_SEPARATOR).trim()
        val series = name.substringAfter(NAME_SEPARATOR, "").trim().takeIf { it.isNotBlank() }
        return act to series
    }

    /**
     * A live night bills its act as one headliner, because a band name carries `&`. A DJ night splits a
     * shared bill into its DJs. A night whose act is its own series names nobody.
     */
    private fun artists(
        act: String,
        series: String?,
        eventType: String
    ): List<ScrapedArtist> {
        if (act.equals(series, ignoreCase = true)) return emptyList()
        val names = if (eventType == EventType.CONCERT.name) listOf(act) else splitSegmentOnConjunctions(act)
        val role = if (eventType == EventType.CONCERT.name) "HEADLINER" else "DJ"
        return names.filterNot { isNonArtistName(it) }.map { ScrapedArtist(name = it, role = role) }
    }

    private fun sourceId(
        date: LocalDate,
        act: String
    ): String = "${EventSource.TAUSEND.sourceIdPrefix}$date-${SlugGenerator.slugify(act)}"

    /**
     * The dated night and its start. The venue prints a midnight start as `24 H` on the night before,
     * and the timestamp names the next morning, so it becomes 23:59 on the printed night, as Junction Bar's does (#2313).
     */
    private fun nightStart(timestamp: String): NightStart? {
        val local =
            try {
                LocalDateTime.parse(timestamp.take(LOCAL_DATE_TIME_LENGTH))
            } catch (_: DateTimeParseException) {
                return null
            }
        return if (local.toLocalTime() == LocalTime.MIDNIGHT) {
            NightStart(local.toLocalDate().minusDays(1), END_OF_NIGHT)
        } else {
            NightStart(local.toLocalDate(), local.toLocalTime())
        }
    }

    private data class NightStart(
        val date: LocalDate,
        val time: LocalTime
    )

    companion object {
        private const val NAME_SEPARATOR = "·"
        private const val LIVE_SERIES = "Live"
        private const val LOCAL_DATE_TIME_LENGTH = 19
        private val END_OF_NIGHT: LocalTime = LocalTime.of(23, 59)
    }
}
