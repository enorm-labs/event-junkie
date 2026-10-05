package de.norm.events.common

/**
 * The public site every absolute URL the API writes points at: the sitemaps and the event feeds.
 * A constant, never the request's host, so a preview or an alias does not name itself canonical.
 * Mirrors `SITE_URL` and `LOCALES` in `events-frontend/src/lib/seo.ts`; change both or neither.
 */
object Site {
    const val URL = "https://event-junkie.de"
    val LOCALES = listOf("en", "de")
    const val DEFAULT_LOCALE = "en"

    /** The feed's public path; nginx forwards it to `/api/events/feed`. Mirrors `FEED_PATH` in `seo.ts`. */
    const val FEED_PATH = "/feed.xml"
}
