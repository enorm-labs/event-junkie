package de.norm.events.scraper

/**
 * [url] without the ad networks' click identifiers, which a link copied from a social-media post
 * carries (#3109). The query is edited as text, so the shop's encoding, an `&amp;` separator and the
 * fragment stay. `utm_*` and `affiliate` stay too: they are the venue's own attribution (#2770).
 */
fun stripClickIdentifiers(url: String): String {
    val queryStart = url.indexOf('?')
    val fragmentStart = url.indexOf('#').takeIf { it >= 0 } ?: url.length
    if (queryStart < 0 || queryStart > fragmentStart) return url
    val parameters = url.substring(queryStart + 1, fragmentStart).split('&')
    val kept = parameters.filterNot { isClickIdentifier(it.removePrefix(ESCAPED_AMPERSAND_TAIL)) }
    if (kept.size == parameters.size) return url
    val query =
        kept
            .filter { it.removePrefix(ESCAPED_AMPERSAND_TAIL).isNotEmpty() }
            .joinToString("&")
            .removePrefix(ESCAPED_AMPERSAND_TAIL)
    return url.substring(0, queryStart) + (if (query.isEmpty()) "" else "?$query") + url.substring(fragmentStart)
}

private fun isClickIdentifier(parameter: String): Boolean = parameter.substringBefore('=').lowercase() in CLICK_IDENTIFIERS

/** Facebook, Google Ads, Microsoft Ads and DoubleClick. */
private val CLICK_IDENTIFIERS = setOf("fbclid", "gclid", "msclkid", "dclid")

/** What follows the `&` of an `&amp;` separator once the query is split on `&`. */
private const val ESCAPED_AMPERSAND_TAIL = "amp;"
