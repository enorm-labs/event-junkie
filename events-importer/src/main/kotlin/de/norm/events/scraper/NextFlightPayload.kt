package de.norm.events.scraper

import org.jsoup.nodes.Document
import tools.jackson.databind.json.JsonMapper

// Shared reader for the Next.js App Router sites this project scrapes (ROSA). Next ships a page's
// server data as a flight payload — `self.__next_f.push([1, "<escaped JSON fragment>"])` chunks
// that the client concatenates — which is a machine-readable source (ADR-007 §"Prefer a JSON / API
// Source") rather than the rendered markup. Only the extraction lives here; every venue keeps its
// own field mapping.

private const val FLIGHT_MARKER = "self.__next_f.push"

private val jsonMapper = JsonMapper.builder().build()

/**
 * Concatenates the flight payload of a Next.js App Router page, with the chunks' JSON escaping
 * undone. Returns an empty string for a page that carries none.
 *
 * A single chunk is not valid JSON by itself and the fragments split mid-value, so the result is
 * text to search rather than a document to parse — see [jsonArrayAt].
 */
fun nextFlightPayload(document: Document): String =
    document
        .select("script")
        .asSequence()
        .map { it.data() }
        .filter { it.contains(FLIGHT_MARKER) }
        .flatMap { flightChunks(it) }
        .map { jsonMapper.readValue(it, String::class.java) }
        .joinToString("")

/**
 * The JSON string literals a script pushes: `push([1,"…"])`, one per chunk, quotes included.
 *
 * Scanned rather than matched. A chunk runs to several kilobytes, and the regex for an escaped
 * string literal (`"(?:\\.|[^"\\])*"`) overflows the stack on one — `java.util.regex` recurses
 * per repetition.
 */
private fun flightChunks(script: String): Sequence<String> =
    sequence {
        var index = script.indexOf(FLIGHT_MARKER)
        while (index >= 0) {
            val opening = script.indexOf('"', index)
            if (opening < 0) return@sequence
            val closing = endOfStringLiteral(script, opening)
            if (closing < 0) return@sequence
            yield(script.substring(opening, closing + 1))
            index = script.indexOf(FLIGHT_MARKER, closing)
        }
    }

/** The index of the quote closing the literal that starts at [opening], or -1 when it is unclosed. */
private fun endOfStringLiteral(
    script: String,
    opening: Int
): Int {
    var index = opening + 1
    while (index < script.length) {
        when (script[index]) {
            '\\' -> index++
            '"' -> return index
        }
        index++
    }
    return -1
}

/**
 * Cuts the JSON array that [key] names out of a flight [payload], brackets balanced, or `null`
 * when the key is absent or its array never closes.
 *
 * Quotes and escapes are tracked so a `[` inside a string value cannot end the array early.
 */
@Suppress("ReturnCount") // Guard clauses for the absent key and the unclosed array are clearer than nesting
fun jsonArrayAt(
    payload: String,
    key: String
): String? {
    val start = payload.indexOf("\"$key\":[").takeIf { it >= 0 }?.plus(key.length + 3) ?: return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until payload.length) {
        val character = payload[index]
        when {
            escaped -> {
                escaped = false
            }

            character == '\\' && inString -> {
                escaped = true
            }

            character == '"' -> {
                inString = !inString
            }

            inString -> {
                continue
            }

            character == '[' || character == '{' -> {
                depth++
            }

            character == ']' || character == '}' -> {
                depth--
                if (depth == 0) return payload.substring(start, index + 1)
            }
        }
    }
    return null
}
