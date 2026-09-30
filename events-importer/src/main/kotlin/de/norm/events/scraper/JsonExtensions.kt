package de.norm.events.scraper

import tools.jackson.databind.JsonNode

/**
 * Reads a trimmed string [field] from this node, or `null` when the field is missing, JSON
 * `null`, blank, or holds an object or an array.
 *
 * The `""` default is what covers the structured case: [JsonNode.asString] without one throws
 * for an object or an array, and schema.org payloads do put an object where a string is
 * expected — `image` arrives as an `ImageObject` on some venues. A missing value is not worth
 * an exception, so every shape that is not a scalar reads as absent.
 */
fun JsonNode.stringOrNull(field: String): String? = path(field).asString("").trim().takeIf { it.isNotBlank() }
