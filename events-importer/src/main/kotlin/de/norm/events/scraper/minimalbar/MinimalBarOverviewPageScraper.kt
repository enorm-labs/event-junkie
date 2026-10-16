package de.norm.events.scraper.minimalbar

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.RunningOrderClock
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.flightStringOrNull
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.jsonArrayAt
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.nextFlightPayload
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

private const val EVENTS_KEY = "events"

/** The only category the site has used; anything else keeps the party type as a fallback. */
private const val DJ_SET_CATEGORY = "dj-set"

/** A resident with unnamed company: `Jago K. & Freunde`, `Sven Swade und Freunde`, `Mister Teaser & Guest`. */
private val CREW_TAIL = Regex("""\s+(?:&|und|and)\s+(?:freunde|friends|guests?|gäste)$""", RegexOption.IGNORE_CASE)

/** The bar's own name after a resident: `Luca ( Minimal Bar)`. */
private val HOUSE_TAG = Regex("""\s*\(\s*minimal\s+bar\s*\)""", RegexOption.IGNORE_CASE)

/** A label in square brackets after the act and its origin: `Mister Teaser (PT) [Slime]`. */
private val LABEL_TAG = Regex("""\s*\[[^\[\]]+]\s*$""")

/** React moves a long string out of the payload and leaves a reference such as `$2c` in its place. */
private val FLIGHT_REFERENCE = Regex("""^\$[0-9a-f]+$""")

/**
 * Pure parser for Minimal Bar's `/dates/` page, read from its Next.js flight payload (ADR-007
 * §"Prefer a JSON / API Source"). The cards print the lineup as one joined line; the payload keeps
 * each act apart with its own set time, an ISO date and a category.
 *
 * Every night carries the same house text about the bar, so a description all nights share is
 * dropped. The same resident is billed bare one night and `& Freunde` the next, so the crew tail
 * comes off or one DJ becomes two artists.
 */
class MinimalBarOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper = JsonMapper.builder().build()

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = jsonArrayAt(nextFlightPayload(document), EVENTS_KEY)
        if (events == null) {
            logger.warn { "No events array in Minimal Bar's flight payload" }
            return emptyList()
        }
        val nodes = jsonMapper.readTree(events).toList()
        val houseText = houseText(nodes)
        return nodes.mapSkippingFailures(logger, "Minimal Bar event") { toScrapedEvent(it, baseUrl, houseText) }
    }

    /**
     * The description most nights carry, which describes the bar rather than the night. A majority, not
     * every night, so one night with its own text does not bring the house text back on the others.
     */
    private fun houseText(nodes: List<JsonNode>): String? =
        nodes
            .mapNotNull { text(it, "description") }
            .groupingBy { it }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 && it.value * 2 > nodes.size }
            ?.key

    private fun toScrapedEvent(
        node: JsonNode,
        baseUrl: String,
        houseText: String?
    ): ScrapedEvent? {
        val slug = text(node, "slug") ?: return skip("?", "no slug")
        val title = text(node, "title") ?: return skip(slug, "no title")
        val date = text(node, "date")?.let(::berlinDate) ?: return skip(slug, "no usable date")

        return ScrapedEvent(
            title = title,
            description = text(node, "description")?.takeUnless { it == houseText },
            eventType = EventType.PARTY.name,
            typeIsFallback = text(node, "category") != DJ_SET_CATEGORY,
            eventDate = date,
            startTime = parseTime(text(node, "startTime")),
            imageUrl = text(node, "image")?.takeIf { it.startsWith("https://") },
            sourceUrl = resolveUrl(baseUrl, "/dates/$slug/"),
            sourceId = "${EventSource.MINIMAL_BAR.sourceIdPrefix}$slug",
            genre = genres(node),
            // The bar states free entrance for every night, and the payload has no price field.
            free = true,
            artists = lineup(node, date)
        )
    }

    /** The night's own styles; empty on every night so far, when the house genre stands in. */
    private fun genres(node: JsonNode): String? =
        node
            .path("genres")
            .mapNotNull { it.asString("").blankToNull() }
            .joinToString(", ")
            .ifEmpty { null }

    /** Each act of the night as a DJ, with the set start the venue gives it. */
    private fun lineup(
        node: JsonNode,
        night: LocalDate
    ): List<ScrapedArtist> {
        val clock = RunningOrderClock(night)
        return node
            .path("lineup")
            .flatMap { slot ->
                val billing = text(slot, "name") ?: return@flatMap emptyList()
                val setStart = parseTime(text(slot, "time"))?.let { clock.slot(it).first }
                actsOf(billing).map { ScrapedArtist(name = it, role = ArtistRole.DJ.name, setStart = setStart) }
            }.distinctBy { SlugGenerator.slugify(it.name) }
    }

    private fun actsOf(billing: String): List<String> =
        splitSegmentOnConjunctions(billing.replace(WHITESPACE, " ").trim())
            .map { act ->
                stripArtistSuffix(
                    act
                        .trim()
                        .replace(CREW_TAIL, "")
                        .replace(HOUSE_TAG, "")
                        .replace(LABEL_TAG, "")
                )
            }.filterNot(::isNonArtistName)

    private fun text(
        node: JsonNode,
        field: String
    ): String? = node.flightStringOrNull(field)?.takeUnless { FLIGHT_REFERENCE.matches(it) }

    private fun berlinDate(timestamp: String): LocalDate? =
        try {
            Instant.parse(timestamp).atZone(BERLIN).toLocalDate()
        } catch (_: DateTimeParseException) {
            null
        }

    private fun skip(
        slug: String,
        reason: String
    ): ScrapedEvent? {
        logger.warn { "Skipping Minimal Bar event $slug: $reason" }
        return null
    }
}
