package de.norm.events.image

import java.util.Locale

/**
 * Percent-encodes the characters `URI` refuses outside the host, which a browser encodes itself:
 * a space at Wild at Heart, square brackets at Theater im Delphi (#2900). `%` is not one of them,
 * so an escaped URL is never double-encoded. The authority is skipped, because an IPv6 host is bracketed.
 */
internal fun String.encodeForUri(): String {
    val authorityStart = indexOf("://").takeIf { it >= 0 }?.plus("://".length) ?: 0
    val pathStart = indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 } ?: return this
    return take(pathStart) + drop(pathStart).map { if (it in URI_ILLEGAL_CHARS) "%%%02X".format(Locale.ROOT, it.code) else "$it" }.joinToString("")
}

private val URI_ILLEGAL_CHARS = setOf(' ', '[', ']', '|', '{', '}', '"', '<', '>', '^', '`', '\\')
