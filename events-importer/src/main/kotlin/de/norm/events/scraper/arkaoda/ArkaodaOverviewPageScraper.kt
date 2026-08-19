package de.norm.events.scraper.arkaoda

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for arkaoda Berlin's `?/default/program` listing page.
 *
 * The site is hand-coded PHP whose router lives in the query string, so the whole
 * programme is one server-rendered page of `div.box` blocks — one per **upcoming**
 * event, past events dropping off, which makes the listing short (often only a
 * handful of blocks). Each block holds a flyer thumbnail, a `.excerpt` container with
 * the `<b>` header run (date, weekday, optional `// Konser`), the title in
 * `h6 > a.heading` linking to `?/default/detail/id=<n>`, and a CSS-truncated body.
 *
 * The overview is the **discovery** page: it identifies every event and its detail
 * URL, and supplies every field the venue publishes except the description. Its body
 * text is deliberately *not* read — it is cut off mid-sentence with a `•••
 * weiterlesen…` marker, so storing it would put visibly broken prose in front of
 * users; the untruncated description comes from [ArkaodaDetailPageScraper] instead.
 *
 * There is no pagination: the page has no next-page link and no query parameter that
 * yields more events, so ADR-007's "first page only" rule is satisfied trivially.
 *
 * @see ArkaodaFieldMapping for the header/title/type/artist rules shared with the detail page.
 * @see ArkaodaWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://berlin.arkaoda.com/?/default/program">arkaoda Berlin programme</a>
 */
class ArkaodaOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event blocks from the programme listing document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the relative detail and flyer links.
     * @return a list of [ScrapedEvent] instances, one per block.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        // An event block is a `.box` that actually carries a titled detail link — the
        // `:has()` guard skips the layout `.box` wrappers the theme also renders.
        val blocks = document.select("#posts-list .box:has(.excerpt h6 a.heading)")
        logger.info { "Found ${blocks.size} event block(s) on the arkaoda programme listing" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed blocks without aborting the import
        return blocks.mapNotNull { block ->
            try {
                parseBlock(block, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse arkaoda event block, skipping" }
                null
            }
        }
    }

    /**
     * Parses one listing block into a [ScrapedEvent], or `null` when a required field
     * is missing — a blank title, an unresolvable detail id, or an unparseable date.
     *
     * The date is required here rather than deferred to the detail page: the two pages
     * render the *same* header markup, so a listing block with no date has none on its
     * detail page either, and letting it through would only be dropped later by
     * [AbstractTwoPageWebsiteImporter][de.norm.events.scraper.AbstractTwoPageWebsiteImporter]
     * after a wasted fetch.
     */
    @Suppress("ReturnCount") // Guard clauses for the required title/id/date are clearer than nesting
    private fun parseBlock(
        block: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val excerpt = block.selectFirst(".excerpt") ?: return null
        val titleLink = excerpt.selectFirst("h6 a.heading") ?: return null
        val rawTitle = titleLink.text().trim()
        if (rawTitle.isBlank()) {
            logger.warn { "arkaoda listing block has no title, skipping" }
            return null
        }

        val href = titleLink.attr("href").takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val eventId = extractEventId(sourceUrl)
        if (eventId == null) {
            logger.warn { "arkaoda detail link '$sourceUrl' for '$rawTitle' carries no numeric id, skipping" }
            return null
        }

        val header = parseArkaodaHeader(excerpt)
        val eventDate = header.eventDate
        if (eventDate == null) {
            logger.warn { "Could not parse a date for arkaoda event '$rawTitle' ($sourceUrl), skipping" }
            return null
        }

        val title = arkaodaTitle(rawTitle)
        val eventType = arkaodaEventType(header.category, title)

        return ScrapedEvent(
            title = title,
            eventType = eventType,
            eventDate = eventDate,
            imageUrl = parseImageUrl(block, baseUrl),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.ARKAODA.sourceIdPrefix}$eventId",
            artists = arkaodaArtists(title, eventType),
            promoters = arkaodaPromoters(title)
        )
    }

    /** Resolves the flyer thumbnail, which the theme links relative to the site root. */
    private fun parseImageUrl(
        block: Element,
        baseUrl: String
    ): String? {
        val src =
            block
                .selectFirst("a.highslide img")
                ?.attr("src")
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { resolveUrl(baseUrl, src) }.getOrNull()
    }

    private companion object {
        /**
         * The numeric event id in a `?/default/detail/id=<n>` link — the site's own
         * primary key and the only stable identity an event has (titles and dates are
         * both edited in place), so it is the basis for [ScrapedEvent.sourceId].
         */
        private val EVENT_ID_PATTERN = Regex("""\bid=(\d+)""")

        fun extractEventId(url: String): String? = EVENT_ID_PATTERN.find(url)?.groupValues?.get(1)
    }
}
