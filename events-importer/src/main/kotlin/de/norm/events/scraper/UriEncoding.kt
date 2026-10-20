package de.norm.events.scraper

import java.util.Locale

/**
 * Percent-encodes what a browser encodes itself and `URI` or a server refuses outside the host: a space
 * at Wild at Heart, square brackets at Theater im Delphi (#2900), a Cyrillic file name at Privatclub
 * (#3639). A non-ASCII character becomes its UTF-8 bytes. `%` is left alone, so an escaped URL is never
 * double-encoded. The authority is skipped, because an IPv6 host is bracketed.
 */
fun String.encodeForUri(): String {
    val authorityStart = indexOf("://").takeIf { it >= 0 }?.plus("://".length) ?: 0
    val pathStart = indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 } ?: return this
    val encoded = StringBuilder(take(pathStart))
    drop(pathStart).codePoints().forEach { codePoint ->
        if (codePoint > MAX_ASCII || codePoint.toChar() in URI_ILLEGAL_CHARS) {
            Character.toString(codePoint).toByteArray(Charsets.UTF_8).forEach { encoded.append("%%%02X".format(Locale.ROOT, it.toInt() and BYTE_MASK)) }
        } else {
            encoded.appendCodePoint(codePoint)
        }
    }
    return encoded.toString()
}

private const val MAX_ASCII = 0x7F
private const val BYTE_MASK = 0xFF

private val URI_ILLEGAL_CHARS = setOf(' ', '[', ']', '|', '{', '}', '"', '<', '>', '^', '`', '\\')
