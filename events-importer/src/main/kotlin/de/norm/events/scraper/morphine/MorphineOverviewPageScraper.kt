package de.norm.events.scraper.morphine

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseGermanShortDate
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Morphine Raum's `/events` listing (overview) page.
 *
 * The listing is a single `div.list-events` holding one `<li>` per upcoming night, each an
 * `a.link-overlay` to the event's `/events/<slug>` detail page wrapping exactly two `<span>`s:
 * the `DD.MM.YY` date and the event title. There are no month headings, no pagination and no
 * status badges — the whole upcoming programme is on this one page, and a night that has passed
 * simply leaves it.
 *
 * The last `<li>` is **not an event**: it links to the venue's `/events/ARCHIVE` page of past
 * nights and renders an empty date span. A dateless row is therefore skipped outright rather than
 * carried forward as an
 * [UNRESOLVED_EVENT_DATE][de.norm.events.scraper.UNRESOLVED_EVENT_DATE] — it is a navigation link,
 * so fetching its (very large) page only to drop the result after the merge would be wasted work.
 *
 * The overview carries the discovery list, date and title; everything else — door and start
 * times, lineup, description, image and pricing — lives on the detail page. Because
 * [MorphineWebsiteImporter] falls back to this data when a detail page fails to fetch, headliner
 * artists are still derived from the title here, with the venue's "Live Recording" framing
 * stripped off the derived name ([stripLiveRecordingSuffix]).
 *
 * @see MorphineDetailPageScraper for the detail-page data source (times, lineup, prices, image).
 * @see MorphineWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="http://www.morphinerecords.com/events">Morphine Raum event listing</a>
 */
class MorphineOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event rows from the overview page document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve detail links and
     *   build `sourceId` values.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("div.list-events a.link-overlay[href]")
        logger.info { "Found ${rows.size} listing row(s) on Morphine Raum overview" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed rows without aborting the whole import
        return rows.mapNotNull { row ->
            try {
                parseRow(row, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Morphine Raum listing row, skipping" }
                null
            }
        }
    }

    /**
     * Parses a single listing row into a [ScrapedEvent], or `null` when it is not an event — the
     * dateless `ARCHIVE` navigation row, or a row missing its title.
     */
    @Suppress("ReturnCount") // Guard clauses for the non-event and malformed rows are clearer than nesting
    private fun parseRow(
        row: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val spans = row.select("span")
        if (spans.size < SPANS_PER_ROW) return null

        // A blank date span marks the ARCHIVE link rather than a night with an unknown date.
        val eventDate = parseGermanShortDate(spans[DATE_SPAN].text()) ?: return null
        val title = spans[TITLE_SPAN].text().takeIf { it.isNotBlank() }?.let(::cleanEventTitle) ?: return null

        val sourceUrl = resolveUrl(baseUrl, row.attr("href"))
        return ScrapedEvent(
            title = title,
            eventType = inferConcertVenueType(title),
            eventDate = eventDate,
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.MORPHINE.sourceIdPrefix}${extractEventSlug(sourceUrl)}",
            artists = headlinersFromTitle(stripLiveRecordingSuffix(title))
        )
    }

    private companion object {
        /** A listing row wraps exactly two spans: the date, then the title. */
        private const val SPANS_PER_ROW = 2
        private const val DATE_SPAN = 0
        private const val TITLE_SPAN = 1
    }
}
