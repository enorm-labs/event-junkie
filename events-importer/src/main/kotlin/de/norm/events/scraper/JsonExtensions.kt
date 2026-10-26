package de.norm.events.scraper

import tools.jackson.databind.JsonNode
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
 * The mapper every scraper reads JSON with. Jackson 3 ignores unknown fields by default, and the Kotlin
 * module binds Kotlin classes. A mapper is immutable, so one instance serves every import.
 */
val SCRAPER_JSON: JsonMapper =
    JsonMapper
        .builder()
        .addModule(kotlinModule())
        .build()
