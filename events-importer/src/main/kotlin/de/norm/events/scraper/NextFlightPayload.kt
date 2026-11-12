package de.norm.events.scraper

import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode

// Shared reader for the Next.js App Router sites this project scrapes (ROSA). Next ships a page's
// server data as a flight payload — `self.__next_f.push([1, "<escaped JSON fragment>"])` chunks
// that the client concatenates — which is a machine-readable source (ADR-007 §"Prefer a JSON / API
// Source") rather than the rendered markup. Only the extraction lives here; every venue keeps its
// own field mapping.

private const val FLIGHT_MARKER = "self.__next_f.push"

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
        .map { SCRAPER_JSON.readValue(it, String::class.java) }
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

/**
 * The index of the quote closing the literal that starts at [opening], or -1 when it is unclosed.
 * Shared with the GoDaddy calendar props (Karakas), another literal too long for a regex.
 */
internal fun endOfStringLiteral(
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
fun jsonArrayAt(
    payload: String,
    key: String
): String? {
    val start = payload.indexOf("\"$key\":[").takeIf { it >= 0 }?.plus(key.length + 3) ?: return null
    return balancedJsonAt(payload, start)
}

/**
 * Cuts the JSON array or object that opens at [start] out of [payload], brackets balanced, or `null` when it never
 * closes. For JSON written into a script as a JavaScript literal, where no quoted key leads to it.
 */
fun balancedJsonAt(
    payload: String,
    start: Int
): String? {
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

/** React writes an `undefined` prop into the flight payload as this string. */
private const val FLIGHT_UNDEFINED = "\$undefined"

/** [stringOrNull] for a flight payload node, where an unset field arrives as `"$undefined"`. */
fun JsonNode.flightStringOrNull(field: String): String? = stringOrNull(field)?.takeUnless { it == FLIGHT_UNDEFINED }

/** A value that points at another row of the payload: `"$1a"`. */
private val FLIGHT_ROW_REFERENCE = Regex("""^\$([0-9a-f]+)$""")

/**
 * The text [value] stands for: itself, or the text row a `"$1a"` reference points at, or `null` when that row is absent.
 * React moves a long string out of the JSON into a row of its own, `1a:T563,<text>`, with its length in UTF-8 bytes in hex.
 * The next row follows the text with no separator, so only the length says where the text ends.
 */
fun flightText(
    payload: String,
    value: String
): String? {
    val id = FLIGHT_ROW_REFERENCE.matchEntire(value)?.groupValues?.get(1) ?: return value
    val header = Regex("""(?<![0-9a-f])$id:T([0-9a-f]+),""").find(payload) ?: return null
    return utf8Prefix(payload, header.range.last + 1, header.groupValues[1].toInt(HEX))
}

private const val HEX = 16

/** The text from [start] that fills [bytes] UTF-8 bytes. */
private fun utf8Prefix(
    text: String,
    start: Int,
    bytes: Int
): String {
    var used = 0
    var end = start
    while (end < text.length && used < bytes) {
        val codePoint = text.codePointAt(end)
        used += utf8Length(codePoint)
        end += Character.charCount(codePoint)
    }
    return text.substring(start, end)
}

@Suppress("MagicNumber") // The UTF-8 encoding's own boundaries.
private fun utf8Length(codePoint: Int): Int =
    when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
