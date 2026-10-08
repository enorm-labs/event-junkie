package de.norm.events.scraper.richten25

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.isNonArtistName
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure parser for the Richten25 events page, a Hostinger site builder page. The programme is a hand-written HTML
 * embed whose source sits in the `props` of the page's Astro island, not in the rendered markup: a
 * `section.event-list` with an "Upcoming Events" heading and one `li` per night.
 *
 * Each night is `<strong>October 8, 2026: </strong>` and a lineup, its sets parted by `//`. A set reads either
 * `Name, Name & Name`, which bills each musician, or `Band: members`, which bills the band. A `… Series:` prefix names
 * the curated series, not an act, so its members are billed. A second embed lists past nights and is not read.
 * The page names no time, price or image.
 */
class Richten25OverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val embed = document.select("astro-island[props]").firstNotNullOfOrNull { upcomingEmbed(it.attr("props")) }
        if (embed == null) {
            logger.warn { "No 'Upcoming Events' embed in the Richten25 page props" }
            return emptyList()
        }
        val nights = Jsoup.parseBodyFragment(embed).select("section.event-list li:has(strong)").mapNotNull { night(it.text(), baseUrl) }
        logger.info { "Found ${nights.size} Richten25 night(s)" }
        return nights
            .groupBy { it.eventDate }
            .values
            .flatMap { sameDay -> sameDay.mapIndexed { index, event -> if (index == 0) event else event.copy(sourceId = "${event.sourceId}-${index + 1}") } }
    }

    /** The embed's HTML, found by its heading anywhere in the island's props, whose shape is the builder's. */
    private fun upcomingEmbed(props: String): String? {
        val root =
            try {
                MAPPER.readTree(props)
            } catch (e: JacksonException) {
                logger.warn(e) { "Unreadable Astro island props on the Richten25 page" }
                return null
            }
        return strings(root).firstOrNull { "event-list" in it && UPCOMING in it }
    }

    private fun strings(node: JsonNode): Sequence<String> = if (node.isString) sequenceOf(node.asString("")) else node.asSequence().flatMap(::strings)

    private fun night(
        text: String,
        baseUrl: String
    ): ScrapedEvent? {
        val match = NIGHT.find(text.trim())
        val date = match?.let { dateOf(it.groupValues[1]) }
        if (match == null || date == null) {
            logger.warn { "Richten25 night '$text' has no readable date, skipping" }
            return null
        }
        val lineup = match.groupValues[2].trim()
        return ScrapedEvent(
            title = lineup,
            eventType = EventType.CONCERT.name,
            eventDate = date,
            sourceUrl = baseUrl,
            sourceId = "${EventSource.RICHTEN25.sourceIdPrefix}$date",
            artists = artistsOf(lineup)
        )
    }

    private fun dateOf(text: String): LocalDate? =
        try {
            LocalDate.parse(text, DATE_FORMAT)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun artistsOf(lineup: String): List<ScrapedArtist> =
        lineup
            .split(SET_SEPARATOR)
            .flatMap { set ->
                val prefixed = PREFIX.find(set)
                when {
                    prefixed == null -> set.split(MEMBER_SEPARATOR)
                    SERIES.containsMatchIn(prefixed.groupValues[1]) -> prefixed.groupValues[2].split(MEMBER_SEPARATOR)
                    else -> listOf(prefixed.groupValues[1])
                }
            }.map { it.trim() }
            .filter { it.isNotEmpty() && !isNonArtistName(it) }
            .distinct()
            .map { ScrapedArtist(name = it) }

    private companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
        const val UPCOMING = "Upcoming Events"
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
        val NIGHT = Regex("""^([A-Z][a-z]+ \d{1,2}, \d{4}):\s*(.+)$""")
        val SET_SEPARATOR = Regex("""\s*//\s*""")
        val PREFIX = Regex("""^\s*([^:,&]+?):\s*(.+)$""")
        val SERIES = Regex("""\bseries\b""", RegexOption.IGNORE_CASE)
        val MEMBER_SEPARATOR = Regex(""",\s*|\s+&\s+|\s+and\s+""")
    }
}
