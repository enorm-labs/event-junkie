package de.norm.events.scraper.gaertenderwelt

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for one page of the Gärten der Welt `/events/veranstaltungen/` listing. TYPO3
 * with the `events2` extension server-renders a `.tx-events2 .list` of `.eventWrapper` rows, five
 * to a page, ascending by date:
 *
 * | Field       | Source                                                                     |
 * |-------------|----------------------------------------------------------------------------|
 * | date + time | the `YYYY-MM-DD_HHmm` stamp in the detail `href` (see [parseEventPath])     |
 * | category    | `.category` — "Konzerte", "Führungen", "Open-Air Kino", … or empty          |
 * | title       | `h3.media-heading a`, occasionally badged ("AUSGEBUCHT: …")                 |
 * | teaser      | `p.textMedium` — one line, stored as the subtitle                           |
 * | image       | `figure img` — the listing's `csm_` crop; the detail page has a larger one  |
 * | ticket shop | `a.ticket` — an absolute bookingkit / Eventim / Ticketfritz link, when sold |
 * | detail page | the `href` to `detail/<stamp>/<slug>/`                                      |
 *
 * The rendered `.date` and `.time` cells are not parsed: the stamp carries both, and `.date`
 * renders a multi-day run as a range (`01.09.2026 - 01.11.2026`), `.time` as a span (`17.30 – 21
 * Uhr`). A row whose href has no stamp is skipped with a warning: the stamp is also its identity.
 * Rows filed under a participation format are dropped before a detail fetch
 * ([isProgrammeCategory]).
 *
 * @see GaertenDerWeltDetailPageScraper for the description, prices, doors time and promoter.
 * @see GaertenDerWeltWebsiteImporter for the paginated fetch orchestrator.
 */
class GaertenDerWeltOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the in-scope rows on one listing page, in page order; a row that is out of scope,
     * unstamped or untitled is dropped, and one malformed row never aborts the page.
     *
     * @param baseUrl the URL the document was fetched from, to resolve root-relative links.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed rows without aborting the page
        val events =
            document.select(EVENT_ROW_SELECTOR).mapNotNull { row ->
                try {
                    parseRow(row, baseUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Gärten der Welt listing row on $baseUrl, skipping" }
                    null
                }
            }
        logger.info { "Found ${events.size} in-scope event row(s) on Gärten der Welt listing $baseUrl" }
        return events
    }

    /**
     * The listing's own "nächste" link, or `null` on the last page, the signal
     * [GaertenDerWeltWebsiteImporter] paginates on. Following the rendered link is what makes the
     * walk terminate: TYPO3 clamps an out-of-range page number to the last page, so a counting walk
     * would re-fetch the final page until its own cap. The last page renders no `li.next`.
     */
    fun nextPageUrl(
        document: Document,
        baseUrl: String
    ): String? = document.attrAt(NEXT_PAGE_SELECTOR, "href")?.let { resolveUrl(baseUrl, it) }

    /** Parses a single `.eventWrapper` row, or `null` when it is out of scope or unusable. */
    @Suppress("ReturnCount") // Guard clauses for the skipped-row cases are clearer than nesting
    private fun parseRow(
        row: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val category = row.textAt(".category")
        if (!isProgrammeCategory(category)) return null

        val href = row.attrAt(TITLE_LINK_SELECTOR, "href")
        val rawTitle = row.textAt(TITLE_LINK_SELECTOR)
        if (href == null || rawTitle == null) {
            logger.warn { "Skipping Gärten der Welt row on $baseUrl: no detail link or title" }
            return null
        }

        val sourceUrl = resolveUrl(baseUrl, href)
        val path = parseEventPath(sourceUrl)
        if (path == null) {
            logger.warn { "Skipping Gärten der Welt row $sourceUrl: no YYYY-MM-DD_HHmm stamp in the detail path" }
            return null
        }

        val title = cleanGaertenDerWeltTitle(rawTitle)
        return ScrapedEvent(
            title = title,
            subtitle = row.textAt("p.textMedium"),
            eventType = mapEventType(category, GAERTEN_DER_WELT_CATEGORY_SYNONYMS) ?: inferUnmarkedTitleType(title),
            eventDate = path.date,
            startTime = path.startTime,
            imageUrl = row.attrAt("figure img", "src")?.let { resolveUrl(baseUrl, it) },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.GAERTEN_DER_WELT.sourceIdPrefix}${path.identity}",
            ticketUrl = row.hrefAt("a.ticket"),
            soldOut = isSoldOutTitle(rawTitle),
            status = gaertenDerWeltStatus(rawTitle)
        )
    }

    private companion object {
        /** The listing rows, scoped to the `events2` plugin so no other list on the page can match. */
        private const val EVENT_ROW_SELECTOR = ".tx-events2 .list .eventWrapper"

        /** The row's heading link, which carries both the title and the detail URL. */
        private const val TITLE_LINK_SELECTOR = "h3.media-heading a"

        /** The paginator's "nächste" link, absent on the last page. */
        private const val NEXT_PAGE_SELECTOR = ".paginationWrapper li.next a"
    }
}
