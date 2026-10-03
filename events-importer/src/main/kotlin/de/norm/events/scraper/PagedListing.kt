package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.web.util.UriComponentsBuilder

private val logger = KotlinLogging.logger {}

/** What [scrapeListingPages] read: the events, and whether the walk reached the listing's last page. */
data class ListingPages(
    val events: List<ScrapedEvent>,
    val complete: Boolean
)

/** One page as [walkListingPages] reads it: its [items], and the absolute URL of the page after it, or null on the last. */
data class ListingPage<T>(
    val items: List<T>,
    val next: String?
)

/** What [walkListingPages] read: every page's items in order, the page count, and whether the walk reached the last page. */
data class WalkedListing<T>(
    val items: List<T>,
    val pages: Int,
    val complete: Boolean
)

/**
 * Walks a paged listing of any page type — an HTML document, a JSON page — from its [first] page,
 * reading each with [read] and fetching the next with [fetch], up to [maxPages] (ADR-007
 * §"Pagination — First Page Only"). A later page that fails is logged and ends the walk, keeping what
 * was read, and so does the cap. Either way the result is not [WalkedListing.complete], which skips
 * the stale cleanup for the run (#1980). A next link back to a page already read ends the walk as
 * complete: that is how some month switchers say there is nothing more.
 *
 * The first page is the caller's to fetch, so a failure there still fails the import.
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList") // Intentional: keep the pages already read if a later one fails
suspend fun <P, T> walkListingPages(
    source: EventSource,
    first: P,
    url: String,
    maxPages: Int,
    fetch: suspend (String) -> P,
    read: (P, String) -> ListingPage<T>
): WalkedListing<T> {
    val visited = mutableSetOf(url)
    var page = read(first, url)
    val items = page.items.toMutableList()
    var next = page.next?.takeUnless { it in visited }
    while (next != null && visited.size < maxPages) {
        val pageUrl: String = next
        val content =
            try {
                fetch(pageUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "${source.name} listing page ${visited.size + 1} failed ($pageUrl); importing the ${visited.size} page(s) read" }
                return WalkedListing(items, visited.size, complete = false)
            }
        visited += pageUrl
        page = read(content, pageUrl)
        items += page.items
        next = page.next?.takeUnless { it in visited }
    }
    if (next != null) {
        logger.warn { "${source.name} pagination hit the $maxPages-page cap before the listing ended; later pages were not read" }
    }
    return WalkedListing(items, visited.size, complete = next == null)
}

/**
 * [walkListingPages] over an HTML listing: [scrape] reads each page's events and [nextPage] its
 * next-page URL. An event that moved across a page boundary between two requests is kept once.
 * A later page is fetched without validators: they cover the entry page only.
 */
@Suppress("LongParameterList") // The walk's parameters plus the two per-page readers
suspend fun HtmlFetcher.scrapeListingPages(
    source: EventSource,
    first: Document,
    url: String,
    maxPages: Int,
    nextPage: (Document, String) -> String?,
    scrape: (Document, String) -> List<ScrapedEvent>
): ListingPages {
    val walked =
        walkListingPages(
            source,
            first,
            url,
            maxPages,
            ::fetchDocument
        ) { document, pageUrl -> ListingPage(scrape(document, pageUrl), nextPage(document, pageUrl)) }
    return ListingPages(walked.items.distinctBy { it.sourceId }, walked.complete)
}

/** What [readEach] read: every input's items in order, and whether every input was read. */
data class ReadEach<T>(
    val items: List<T>,
    val complete: Boolean
)

/**
 * Reads each of [inputs] with [read], keeping the others when one fails: month pages, production
 * pages, calendar days. A failure is logged with [failure]'s text and makes the result not
 * [ReadEach.complete], which skips the stale cleanup for the run (#1980).
 */
@Suppress("TooGenericExceptionCaught") // Intentional: one failed input must not cost the others
suspend fun <I, T> readEach(
    inputs: List<I>,
    failure: (I) -> String,
    read: suspend (I) -> List<T>
): ReadEach<T> {
    val results =
        inputs.map { input ->
            try {
                read(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { failure(input) }
                null
            }
        }
    return ReadEach(results.filterNotNull().flatten(), complete = results.none { it == null })
}

/**
 * The absolute URL of a paginator's next-page link at [cssQuery], resolved against the page's base
 * URI, or null on the last page.
 */
fun Element.nextPageUrl(cssQuery: String): String? = selectFirst(cssQuery)?.absUrl("href")?.takeIf { it.isNotEmpty() }

/** This URL's [name] query parameter, or null without one. */
fun String.queryParameter(name: String): String? =
    UriComponentsBuilder
        .fromUriString(this)
        .build()
        .queryParams
        .getFirst(name)

/** This listing URL's page number from its [name] query parameter; a URL without one is page 1. */
fun String.pageNumber(name: String = "page"): Int = queryParameter(name)?.toIntOrNull() ?: 1

/** This URL with its [name] query parameter set to [value], replacing any value it had. */
fun String.withQueryParameter(
    name: String,
    value: Any
): String =
    UriComponentsBuilder
        .fromUriString(this)
        .replaceQueryParam(name, value)
        .build()
        .toUriString()
