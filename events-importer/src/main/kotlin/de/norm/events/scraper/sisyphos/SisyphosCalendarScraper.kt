package de.norm.events.scraper.sisyphos

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant
import java.time.LocalDate

/**
 * Pure parser for the club's own calendar feed (`dashboard.sisyphos-berlin.net/kalender-media/events.json`),
 * which fills the "Upcoming" and "Following Events" iframes on the homepage (ADR-038).
 *
 * Each entry is one night or weekend with a title, a subtitle, an HTML blurb, an image, and its
 * opening and closing as UTC instants (`startRaw`, `endRaw`). The `start` and `end` strings beside
 * them name no year and are not read. A ticketed night carries a `ticketLink` to its shop product.
 * The feed keeps past entries; the upsert drops them. The calendar has no entry id, so the
 * `sourceId` is keyed on the Berlin opening date, the key the shop and sisy.fan share.
 *
 * Every night is stored as a party, the market and the open day included (#2302).
 */
class SisyphosCalendarScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper = JsonMapper.builder().build()

    /** Parses every entry in the calendar feed [json]. */
    fun scrape(json: String): List<ScrapedEvent> {
        val entries = parseEntries(json) ?: return emptyList()
        logger.info { "Found ${entries.size()} entry(ies) in the Sisyphos calendar" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed entries without aborting the import
        val nights =
            entries.mapNotNull { node ->
                try {
                    parseEntry(node)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Sisyphos calendar entry '${node.stringOrNull("title")}', skipping" }
                    null
                }
            }
        return nights
            .groupBy { it.sourceId }
            .map { (sourceId, sameDay) ->
                if (sameDay.size > 1) {
                    logger.warn {
                        "Sisyphos calendar has ${sameDay.size} entries opening on one day ($sourceId): ${sameDay.map { it.title }}; keeping the first"
                    }
                }
                sameDay.first()
            }
    }

    @Suppress("TooGenericExceptionCaught") // A malformed payload must degrade to null, never abort the import
    private fun parseEntries(json: String): JsonNode? {
        val root =
            try {
                jsonMapper.readTree(json)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Sisyphos calendar feed" }
                return null
            }
        return root.takeIf { it.isArray } ?: run {
            logger.warn { "Sisyphos calendar feed is not an array" }
            null
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required title and opening are clearer than nesting
    private fun parseEntry(node: JsonNode): ScrapedEvent? {
        val rawTitle = node.stringOrNull("title")
        if (rawTitle == null) {
            logger.warn { "Sisyphos calendar entry has no title, skipping" }
            return null
        }
        val start = node.stringOrNull("startRaw")?.let { Instant.parse(it).atZone(BERLIN) }
        if (start == null) {
            logger.warn { "Sisyphos calendar entry '$rawTitle' has no opening, skipping" }
            return null
        }
        val end = node.stringOrNull("endRaw")?.let { Instant.parse(it).atZone(BERLIN) }
        val subtitle = node.stringOrNull("subtitle")
        val eventDate: LocalDate = start.toLocalDate()
        return ScrapedEvent(
            title = cleanEventTitle(rawTitle),
            // "Party" is the calendar's default subtitle and says nothing the type does not.
            subtitle = subtitle?.takeUnless { it.equals(PARTY_SUBTITLE, ignoreCase = true) || hasFreeEntryPhrase(it) },
            description = sisyphosHtmlToText(node.stringOrNull("content")),
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = start.toLocalTime(),
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime(),
            imageUrl = node.stringOrNull("image")?.takeIf(::isClubImage),
            sourceUrl = HOMEPAGE_URL,
            sourceId = "${EventSource.SISYPHOS.sourceIdPrefix}$eventDate",
            ticketUrl = node.stringOrNull("ticketLink")?.takeIf { it.startsWith("http") },
            free = hasFreeEntryPhrase(subtitle)
        )
    }

    /** The club's own images; one past entry carried a stock placeholder from images.unsplash.com. */
    private fun isClubImage(url: String): Boolean =
        runCatching { URI(url).host }.getOrNull()?.let { it == CLUB_DOMAIN || it.endsWith(".$CLUB_DOMAIN") } ?: false

    companion object {
        const val FEED_URL = "https://dashboard.sisyphos-berlin.net/kalender-media/events.json"
        const val HOMEPAGE_URL = "https://www.sisyphos-berlin.net/"

        private const val CLUB_DOMAIN = "sisyphos-berlin.net"
        private const val PARTY_SUBTITLE = "Party"
    }
}
