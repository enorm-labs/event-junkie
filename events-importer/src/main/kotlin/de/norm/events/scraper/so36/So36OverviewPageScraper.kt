package de.norm.events.scraper.so36

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

/**
 * Pure HTML parser for SO36's event listing (overview) page.
 *
 * SO36 runs on the "Ticket-Toaster" shop platform. The homepage (`/`) redirects
 * to `/tickets`, which server-renders every upcoming event as an anchor to its
 * `/produkte/<id>-…-am-DD-MM-YYYY` detail page. The visible listing itself is a
 * client-rendered (Knockout.js) grid, but the same anchors are also emitted into
 * a static, server-rendered accessibility list — so the overview needs no
 * JavaScript to enumerate the program.
 *
 * The overview serves two purposes:
 * 1. **Discovery** — identifies all event detail URLs for enrichment.
 * 2. **Fallback data** — the product URL carries a stable numeric id (used for the
 *    `sourceId`) and the event date (`am-DD-MM-YYYY`, four-digit year), and the
 *    link text carries the title. The detail page (the primary source) supplies
 *    everything else. Merging is handled by [So36WebsiteImporter].
 *
 * @see So36DetailPageScraper for the primary per-event data source.
 * @see So36WebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.so36.com/tickets">SO36 program</a>
 */
class So36OverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event links from the overview page document.
     *
     * Every event is an `<a href="/produkte/<id>-…-am-DD-MM-YYYY">`. Non-event
     * shop links (e.g. merch) carry no `am-DD-MM-YYYY` date suffix and are
     * skipped. Events are deduplicated by their numeric product id, since the
     * featured "TONIGHT" teaser repeats an event that is also in the list.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve
     *   relative detail links.
     * @return a list of [ScrapedEvent] instances (one per distinct event).
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val seenProductIds = mutableSetOf<String>()

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed links without aborting the entire import
        val events =
            document.select("a[href*=/produkte/]").mapNotNull { link ->
                try {
                    parseLink(link, baseUrl, seenProductIds)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse event link, skipping" }
                    null
                }
            }
        logger.info { "Found ${events.size} event(s) on SO36 overview" }
        return events
    }

    /**
     * Parses a single product anchor into a [ScrapedEvent], or `null` when the
     * link is not an event (no date suffix) or has already been seen.
     */
    @Suppress("ReturnCount") // Guard clauses for non-event links and duplicates are clearer than nesting
    private fun parseLink(
        link: Element,
        baseUrl: String,
        seenProductIds: MutableSet<String>
    ): ScrapedEvent? {
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val match = PRODUCT_HREF_PATTERN.find(href) ?: return null // not an event ticket link

        val productId = match.groupValues[1]
        if (!seenProductIds.add(productId)) return null // duplicate (e.g. the featured teaser)

        val eventDate =
            LocalDate.of(
                match.groupValues[4].toInt(), // year
                match.groupValues[3].toInt(), // month
                match.groupValues[2].toInt() // day
            )

        return ScrapedEvent(
            title = parseTitle(link.text()) ?: "SO36 $eventDate",
            eventDate = eventDate,
            sourceUrl = resolveUrl(baseUrl, href),
            sourceId = "${EventSource.SO36.sourceIdPrefix}$productId"
        )
    }

    /**
     * Extracts the event title from the link text, which follows the pattern
     * `"Tickets <TITLE> in <City> am DD.MM.YYYY"`. Returns `null` if the text
     * does not match, letting the caller fall back to a placeholder that the
     * detail page's `<h1>` then overrides.
     */
    private fun parseTitle(text: String): String? =
        LINK_TEXT_PATTERN
            .find(text.trim())
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private companion object {
        /**
         * Matches a `/produkte/<id>-…-am-DD-MM-YYYY` event detail path, capturing
         * the numeric product id and the date parts. The `am-…` date suffix is
         * what distinguishes an event ticket from a non-dated shop product.
         */
        private val PRODUCT_HREF_PATTERN =
            Regex("""/produkte/(\d+)-.*-am-(\d{2})-(\d{2})-(\d{4})""")

        /** Matches the `"Tickets <TITLE> in <City> am <date>"` link text, capturing the title. */
        private val LINK_TEXT_PATTERN = Regex("""^Tickets\s+(.+?)\s+in\s+\S+\s+am\s+\d""")
    }
}
