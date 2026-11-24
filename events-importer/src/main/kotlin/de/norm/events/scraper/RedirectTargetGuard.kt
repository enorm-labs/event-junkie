package de.norm.events.scraper

/**
 * Decides whether [RedirectFollowingFilter] may send a request or a redirect hop to a URL (#5444, #5448).
 *
 * A scraped page URL and a hop's `Location` both come from a server we do not control, so requesting either unchecked
 * is server-side request forgery (ADR-019 §4). `ImageUrlValidator` in the `image` module is the one implementation. An interface here rather than that
 * class, because `image` depends on `scraper`, and the reverse edge would be a cycle.
 */
fun interface RedirectTargetGuard {
    /** The reason [url] must not be requested, or null when it may be. */
    fun reject(url: String): String?
}
