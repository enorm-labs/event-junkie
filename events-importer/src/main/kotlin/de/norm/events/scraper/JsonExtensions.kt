package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KLogger
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Reads a trimmed string [field] from this node, or `null` when the field is missing, JSON
 * `null`, blank, or holds an object or an array.
 *
 * The `""` default is what covers the structured case: [JsonNode.asString] without one throws
 * for an object or an array, and schema.org payloads do put an object where a string is
 * expected — `image` arrives as an `ImageObject` on some venues. A missing value is not worth
 * an exception, so every shape that is not a scalar reads as absent.
 */
fun JsonNode.stringOrNull(field: String): String? = path(field).asString("").blankToNull()

/**
 * The HTML a WordPress REST post holds under [field]`.rendered` (`title`, `excerpt`, `content`), trimmed, or `null` when blank.
 * Entities stay encoded: each venue decodes or flattens the HTML in its own way.
 */
fun JsonNode.wpRendered(field: String): String? = path(field).stringOrNull("rendered")

/**
 * Parses [json] into a tree, or logs [message] at `WARN` with the exception and returns `null`. A malformed payload
 * degrades a source to "no events", never a failed import. Every Jackson 3 exception is a [JacksonException], so the
 * catch stays narrow.
 */
fun ObjectMapper.readTreeOrNull(
    json: String,
    logger: KLogger,
    message: () -> String
): JsonNode? =
    try {
        readTree(json)
    } catch (e: JacksonException) {
        logger.warn(e, message)
        null
    }

/**
 * The mapper every scraper reads JSON with. Jackson 3 ignores unknown fields by default, and the Kotlin
 * module binds Kotlin classes. A mapper is immutable, so one instance serves every import.
 */
val SCRAPER_JSON: JsonMapper =
    JsonMapper
        .builder()
        .addModule(kotlinModule())
        .build()

/**
 * [SCRAPER_JSON] with the snake_case naming strategy, for an API that names its fields in snake_case
 * and a DTO that names its properties in camelCase: no `@JsonProperty` per field. A key the strategy
 * cannot derive, one with a leading underscore or a colon, still names itself at the property.
 */
val SCRAPER_JSON_SNAKE_CASE: JsonMapper =
    SCRAPER_JSON
        .rebuild()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .build()
