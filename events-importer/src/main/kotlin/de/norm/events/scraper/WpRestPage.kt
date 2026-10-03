package de.norm.events.scraper

// The page walk for a WordPress REST collection (`/wp-json/wp/v2/…`, the WooCommerce Store API). The
// venue builds its own page URL and parses its own page; the stop rule lives here.

/** One parsed page of a WordPress collection, as a venue's scraper returns it. */
interface WpRestPage<out T> {
    val items: List<T>

    /** The raw post count of the response, not the parsed [items], so a post that fails to parse cannot end the walk. */
    val postCount: Int
}

/**
 * Walks a WordPress collection from page 1, reading each page with [read], up to [maxPages]. The
 * page URL carries its number as `page=`. A page with fewer than [perPage] posts is the last:
 * WordPress answers the page after it with `400 rest_post_invalid_page_number`. [lastPage] ends the
 * walk earlier, for a venue whose ordering says the rest is past.
 */
@Suppress("LongParameterList") // The walk's parameters plus the venue's URL, reader and stop rule
suspend fun <T, P : WpRestPage<T>> ApiClient.walkWpRestPages(
    source: EventSource,
    pageUrl: (Int) -> String,
    perPage: Int,
    maxPages: Int,
    read: (String) -> P,
    lastPage: (P) -> Boolean = { false }
): WalkedListing<T> {
    val fetchPage: suspend (String) -> P = { read(fetchJson(it)) }
    val firstUrl = pageUrl(1)
    return walkListingPages(source, fetchPage(firstUrl), firstUrl, maxPages, fetchPage) { page, url ->
        ListingPage(page.items, pageUrl(url.pageNumber() + 1).takeUnless { page.postCount < perPage || lastPage(page) })
    }
}
