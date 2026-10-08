package de.norm.events.scraper.houseofmusic

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isFestivalTitle
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.mapWixEventStatus
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.net.URI

/**
 * Pure parser for the House of Music home page, whose Wix Events widget lists the whole programme. Every field comes
 * from the `wix-warmup-data` JSON ([WixEventsWarmupData]); the venue hides each event's own page, so none is read.
 *
 * Every event registers externally. Its link is the ticket link unless it points to Instagram or back to this site,
 * which sell nothing. The type and the acts come from the title, since the payload carries no category or lineup.
 * The house writes `<act> | Album Pre Event`, `<act> - … Concert …`, `<label>: <act>; <act>` and
 * `<festival> | <act> + <act>`; any other title goes to the shared title reader. A title that only ends in "Concert"
 * names no act it can be cut from (`Ragde Lobo Medicine Music Concert`), so it bills none. The two list shapes are a
 * lineup, not a title read as an act, so they are not title-derived: a festival title keeps them.
 */
class HouseOfMusicOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = WixEventsWarmupData.events(document, EventSource.HOUSE_OF_MUSIC) ?: return emptyList()
        logger.info { "Found ${events.size()} event(s) in the House of Music Wix warmup payload" }
        return events.mapSkippingFailures(logger, "House of Music event") { event(it, baseUrl) }
    }

    private fun event(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent {
        val slug = requireNotNull(node.stringOrNull("slug")) { "House of Music event has no slug" }
        val title = cleanEventTitle(node.stringOrNull("title").orEmpty())
        require(title.isNotBlank()) { "House of Music event '$slug' has no title" }
        val schedule = parseWixSchedule(node.path("scheduling").path("config"))
        val date = requireNotNull(schedule.date) { "House of Music event '$slug' has no start date" }
        val type = inferConcertVenueType(title)
        return ScrapedEvent(
            title = title,
            description = node.stringOrNull("description"),
            eventType = type,
            eventDate = date,
            startTime = schedule.startTime,
            endDate = schedule.endDate,
            endTime = schedule.endTime,
            sourceUrl = baseUrl,
            sourceId = "${EventSource.HOUSE_OF_MUSIC.sourceIdPrefix}$slug",
            ticketUrl =
                node
                    .path("registration")
                    .path("external")
                    .stringOrNull("registration")
                    ?.takeIf { sellsTickets(it, baseUrl) },
            status = mapWixEventStatus(node.path("status")),
            artists = actsOf(title, type)
        )
    }

    private fun actsOf(
        title: String,
        type: String
    ): List<ScrapedArtist> {
        val (head, tail) = SEPARATOR.split(title, limit = 2).map { it.trim() } + listOf("")
        val lineup =
            when {
                LABEL_LIST.matches(title) -> title.substringAfter(':').split(';')
                tail.isNotEmpty() && isFestivalTitle(head) -> tail.split(" + ")
                else -> null
            }
        val names =
            when {
                type == EventType.QUIZ.name -> emptyList()
                lineup != null -> lineup
                tail.isNotEmpty() && OCCASION.containsMatchIn(tail) -> listOf(head)
                OCCASION.containsMatchIn(title) -> emptyList()
                else -> return buildArtistsForEventType(title, subtitle = null, eventType = type)
            }
        return names
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "HEADLINER", titleDerived = lineup == null) }
    }

    private fun sellsTickets(
        link: String,
        baseUrl: String
    ): Boolean {
        val host = runCatching { URI(link).host }.getOrNull()?.removePrefix("www.") ?: return false
        return host != "instagram.com" && host != URI(baseUrl).host.removePrefix("www.")
    }

    private companion object {
        val SEPARATOR = Regex("""\s+[|\-–]\s+""")
        val LABEL_LIST = Regex("""^[^:;]+:\s*[^;]+(?:;[^;]+)+$""")
        val OCCASION = Regex("""\b(?:album|release|pre event|concert|konzert)\b""", RegexOption.IGNORE_CASE)
    }
}
