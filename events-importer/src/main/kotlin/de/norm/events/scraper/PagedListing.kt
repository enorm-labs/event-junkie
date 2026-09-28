package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private val logger = KotlinLogging.logger {}

/** What [scrapeListingPages] read: the events, and whether the walk reached the listing's last page. */
data class ListingPages(
    val events: List<ScrapedEvent>,
    val complete: Boolean
)

/**
 * Scrapes a paged listing from its [first] page to the last, following [nextPage] up to [maxPages]
 * (ADR-007 §"Pagination — First Page Only"). A later page is fetched without validators: they cover
 * the entry page only. One that fails is logged and ends the walk, keeping what was read, and so
 * does the cap. Either way the result is not [ListingPages.complete], which skips the stale cleanup
 * for the run (#1980). An event that moved across a page boundary between two requests is kept once.
 *
 * @param nextPage the absolute URL of the page after the given one, or null on the last page.
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList") // Intentional: keep the pages already read if a later one fails
suspend fun HtmlFetcher.scrapeListingPages(
    source: EventSource,
    first: Document,
    url: String,
    maxPages: Int,
    nextPage: (Document, String) -> String?,
    scrape: (Document, String) -> List<ScrapedEvent>
): ListingPages {
    val events = scrape(first, url).toMutableList()
    var next = nextPage(first, url)
    var pages = 1
    while (next != null && pages < maxPages) {
        val pageUrl: String = next
        val document =
            try {
                fetchDocument(pageUrl)
            } catch (e: Exception) {
                logger.warn(e) { "${source.name} listing page ${pages + 1} failed ($pageUrl); importing the $pages page(s) read" }
                break
            }
        events += scrape(document, pageUrl)
        next = nextPage(document, pageUrl)
        pages++
    }
    if (next != null && pages == maxPages) {
        logger.warn { "${source.name} pagination hit the $maxPages-page cap before the listing ended; later pages were not read" }
    }
    return ListingPages(events.distinctBy { it.sourceId }, complete = next == null)
}

/**
 * The absolute URL of a paginator's next-page link at [cssQuery], resolved against the page's base
 * URI, or null on the last page.
 */
fun Element.nextPageUrl(cssQuery: String): String? = selectFirst(cssQuery)?.absUrl("href")?.takeIf { it.isNotEmpty() }
