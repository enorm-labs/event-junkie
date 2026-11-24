package de.norm.events.scraper

import java.net.URI

/** Whether [url] is on one of the [shops] hosts or a subdomain of one; a relative or malformed URL is not. */
internal fun isTicketShop(
    url: String,
    shops: Set<String>
): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
    return shops.any { host == it || host.endsWith(".$it") }
}
