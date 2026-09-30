package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Element
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime

// The one reader for a page's schema.org `Event` JSON-LD, and the fields the venues take from it,
// so a venue that rewraps its block keeps working.

private val logger = KotlinLogging.logger {}

private val jsonLdMapper: JsonMapper = JsonMapper.builder().build()

private const val JSON_LD_SCRIPT = "script[type=application/ld+json]"

/** The `offers.availability` terms that mean no ticket is left. */
private val SOLD_OUT_AVAILABILITY = listOf("SoldOut", "OutOfStock")

/**
 * Every schema.org `Event` node in the JSON-LD scripts at or under this element, in page order.
 * A malformed block is logged and skipped, so it never costs the other blocks.
 */
fun Element.jsonLdEvents(): List<JsonNode> = select(JSON_LD_SCRIPT).flatMap { jsonLdEvents(it.data()) }

/**
 * The schema.org `Event` nodes in one JSON-LD block, whatever the venue wrapped them in: a bare
 * object, an array, an `@graph`, or any nesting of these. Empty when the block does not parse.
 */
fun jsonLdEvents(json: String): List<JsonNode> {
    val root =
        try {
            jsonLdMapper.readTree(json)
        } catch (e: JacksonException) {
            logger.warn(e) { "JSON-LD block is not parseable, skipping it" }
            return emptyList()
        }
    return root.jsonLdNodes().filter { it.isSchemaEvent() }
}

private fun JsonNode.jsonLdNodes(): List<JsonNode> =
    when {
        isArray -> flatMap { it.jsonLdNodes() }
        path("@graph").isArray -> path("@graph").flatMap { it.jsonLdNodes() }
        isObject -> listOf(this)
        else -> emptyList()
    }

/**
 * Whether one of the node's `@type` values is `Event` or a schema.org subtype of it
 * (`MusicEvent`, `TheaterEvent`, `Festival`). A prefixed type (`schema:Event`, a full URL) counts.
 */
fun JsonNode.isSchemaEvent(): Boolean {
    val type = path("@type")
    val names = (if (type.isArray) type.toList() else listOf(type)).map { it.asString("") }
    return names
        .map { it.substringAfterLast('/').substringAfterLast(':') }
        .any { it.endsWith("Event") || it == "Festival" }
}

/** The event's `name` with HTML entities decoded, or `null` when blank. */
fun JsonNode.schemaName(): String? = stringOrNull("name")?.let(::decodeHtmlEntities)?.takeIf { it.isNotBlank() }

/** The date part of a schema.org date [field] (`startDate`, `endDate`), or `null`. */
fun JsonNode.schemaDate(field: String): LocalDate? = stringOrNull(field)?.let(::parseIsoDate)

/**
 * The `HH:mm` of a schema.org time [field]: a timestamp's clock part (`2026-07-31T20:00:00+0200`)
 * or a bare `doorTime` (`19:00`). The offset is ignored: every venue states Berlin local time, and
 * some print it without the colon that `OffsetDateTime` needs. A date-only value has no time.
 */
fun JsonNode.schemaTime(field: String): LocalTime? {
    val value = stringOrNull(field) ?: return null
    val clock = if ('T' in value) value.substringAfter('T') else value.takeIf { parseIsoDate(it) == null }
    return parseTime(clock?.take(HH_MM_LENGTH))
}

/** The event's `eventStatus` as an [EventStatus][de.norm.events.event.EventStatus] name, through [parseSchemaEventStatus]. */
fun JsonNode.schemaStatus(): String = parseSchemaEventStatus(stringOrNull("eventStatus"))

/**
 * The absolute URL of the event's `image`, which venues publish as a string, an `ImageObject`, or
 * an array of either; the first one wins. `null` without an `http` URL.
 */
fun JsonNode.schemaImageUrl(): String? {
    val image = path("image").let { if (it.isArray) it.firstOrNull() else it } ?: return null
    val url = if (image.isObject) image.stringOrNull("url") else image.asString("").trim()
    return url?.takeIf { it.startsWith("http") }
}

/** The event's `offers` as a list, whether the venue publishes one offer object or an array. */
fun JsonNode.schemaOffers(): List<JsonNode> {
    val offers = path("offers")
    return if (offers.isArray) offers.filter { it.isObject } else listOfNotNull(offers.takeIf { it.isObject })
}

/**
 * Whether every offer's `availability` is `SoldOut` or `OutOfStock`. An event with no offers sells
 * nothing online, so it is never sold out.
 */
fun JsonNode.schemaSoldOut(): Boolean {
    val offers = schemaOffers()
    return offers.isNotEmpty() &&
        offers.all { offer ->
            val availability = offer.stringOrNull("availability").orEmpty()
            SOLD_OUT_AVAILABILITY.any { availability.endsWith(it, ignoreCase = true) }
        }
}
