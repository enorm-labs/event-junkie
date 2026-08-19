package de.norm.events.scraper.wuhlheide

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Parkbühne Wuhlheide's `/programm` listing page.
 *
 * The page carries the whole season unpaginated, split into one `.shows` block per year
 * (`Konzerte 2026`, `Konzerte 2027`). Each `.show` holds a German long date, an `h2` act, an
 * `h3` tour name, a poster, an `Ausverkauft` `.statusLabel` and — unless sold out — a ticket
 * link.
 *
 * The **date comes from the URL**, not the rendered German text: every event links to
 * `/programm/<act>/YYYY-MM-DD`, an ISO date that needs no month-name parsing and doubles as the
 * stable [ScrapedEvent.sourceId]. A run of nights by one act is normal here, and the per-date
 * URL is what keeps them apart.
 *
 * @see WuhlheideDetailPageScraper for the detail-page data (times, price, promoter).
 * @see WuhlheideWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.wuhlheide.de/programm">Parkbühne Wuhlheide programme</a>
 */
class WuhlheideOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all shows from the programme page document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the per-event
     *   detail links.
     * @return a list of [ScrapedEvent] instances, one per listed show.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val shows = document.select(".shows .show")
        logger.info { "Found ${shows.size} show(s) on Parkbühne Wuhlheide overview" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed shows without aborting the import
        return shows.mapNotNull { show ->
            try {
                parseShow(show, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Parkbühne Wuhlheide show, skipping" }
                null
            }
        }
    }

    /** Parses a single `.show` block into a [ScrapedEvent], or `null` when it has no link or title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseShow(
        show: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = show.selectFirst("h2 a[href]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, "/programm/")

        // Jsoup renders the act's <wbr> word-break hint away, so "AnnenMay<wbr>Kantereit"
        // comes back as the unbroken "AnnenMayKantereit".
        val title = show.textAt("h2 a")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Parkbühne Wuhlheide show '$slug' has no title, skipping" }
            return null
        }
        val subtitle = show.textAt("h3")
        val eventType = inferConcertVenueType(title)
        val statusLabel = show.textAt(".statusLabel").orEmpty()

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            // The slug's trailing ISO date is the canonical one; the German long date is only rendered.
            eventDate = parseIsoDate(slug.substringAfterLast('/')) ?: UNRESOLVED_EVENT_DATE,
            imageUrl = show.imgSrcAt("img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.WUHLHEIDE.sourceIdPrefix}$slug",
            // Anchored on the ticket icon: the sibling share buttons are also target="_blank".
            ticketUrl = show.hrefAt(".buttons a.button:has(i.fa-ticket)"),
            // "Ausverkauft" is a flag, not a status — the venue publishes no cancellations.
            soldOut = statusLabel.contains(SOLD_OUT_LABEL, ignoreCase = true),
            artists = buildArtistsForEventType(title, subtitle, eventType)
        )
    }

    private companion object {
        /** The venue's sold-out badge text, rendered in either casing. */
        private const val SOLD_OUT_LABEL = "ausverkauft"
    }
}
