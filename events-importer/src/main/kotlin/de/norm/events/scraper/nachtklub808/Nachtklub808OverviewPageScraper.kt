package de.norm.events.scraper.nachtklub808

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.Locale

/**
 * Pure parser for the 808 Nachtklub one-pager, an Astro site. The programme is the `props` of the `EventList` island,
 * in Astro's serialised form, where every value is a `[type, value]` pair. Each night has a `date` (Berlin midnight as a
 * UTC instant), a `name` that may carry a `<span>`, a `/`-separated `djs` list and an optional ticket `url`.
 *
 * The page names no time per night. The start is the club's opening hour for that weekday, from the `NightClub`
 * JSON-LD; a summer pop-up row is not a club night, so it gets none. A `(Host)` in the DJ list is billed like a DJ.
 */
class Nachtklub808OverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val nights =
            document
                .selectFirst("astro-island[opts*=\"EventList\"][props]")
                ?.let { readJson(it.attr("props")) }
                ?.path("data")
                ?.path(1)
        if (nights == null || !nights.isArray) {
            logger.warn { "No EventList island on the 808 Nachtklub page" }
            return emptyList()
        }
        val opens = openingHours(document)
        val events = nights.toList().mapSkippingFailures(logger, "808 Nachtklub night") { night(it.path(1), baseUrl, opens) }
        logger.info { "Found ${events.size} 808 Nachtklub night(s)" }
        return events
            .groupBy { it.eventDate }
            .values
            .flatMap { sameDay -> sameDay.mapIndexed { index, event -> if (index == 0) event else event.copy(sourceId = "${event.sourceId}-${index + 1}") } }
    }

    private fun night(
        node: JsonNode,
        baseUrl: String,
        opens: Map<DayOfWeek, LocalTime>
    ): ScrapedEvent {
        val date = Instant.parse(node.value("date")).atZone(BERLIN).toLocalDate()
        val title = cleanEventTitle(Jsoup.parseBodyFragment(node.value("name")).text())
        require(title.isNotBlank()) { "808 Nachtklub night on $date has no name" }
        val clubNight = !node.path("isSummerEvent").path(1).asBoolean(false)
        return ScrapedEvent(
            title = title,
            eventType = EventType.PARTY.name,
            eventDate = date,
            startTime = opens[date.dayOfWeek]?.takeIf { clubNight },
            sourceUrl = baseUrl,
            sourceId = "${EventSource.NACHTKLUB_808.sourceIdPrefix}$date",
            ticketUrl = node.value("url").ifBlank { null },
            artists = artistsOf(node.value("djs"))
        )
    }

    /** The weekdays and hours the club opens, from the `NightClub` JSON-LD's `openingHoursSpecification`. */
    private fun openingHours(document: Document): Map<DayOfWeek, LocalTime> =
        document
            .select("script[type=application/ld+json]")
            .mapNotNull { readJson(it.data()) }
            .flatMap { it.path("openingHoursSpecification").toList() }
            .flatMap { spec ->
                val opens = runCatching { LocalTime.parse(spec.path("opens").asString("")) }.getOrNull()
                spec.path("dayOfWeek").toList().mapNotNull { day ->
                    val weekday = DayOfWeek.entries.firstOrNull { it.name == day.asString("").substringAfterLast('/').uppercase(Locale.ROOT) }
                    if (weekday == null || opens == null) null else weekday to opens
                }
            }.toMap()

    private fun readJson(text: String): JsonNode? =
        try {
            MAPPER.readTree(text)
        } catch (e: JacksonException) {
            logger.warn(e) { "Unreadable JSON on the 808 Nachtklub page" }
            null
        }

    private fun artistsOf(djs: String): List<ScrapedArtist> =
        djs
            .split('/')
            .map { it.replace(HOST, "").trim() }
            .filter { it.isNotEmpty() && !isNonArtistName(it) }
            .distinct()
            .map { ScrapedArtist(name = it) }

    private fun JsonNode.value(field: String): String = path(field).path(1).asString("")

    private companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
        val HOST = Regex("""\(\s*host\s*\)""", RegexOption.IGNORE_CASE)
    }
}
