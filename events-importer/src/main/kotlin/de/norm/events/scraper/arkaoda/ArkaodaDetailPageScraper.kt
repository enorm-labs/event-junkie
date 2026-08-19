package de.norm.events.scraper.arkaoda

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textLinesAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for arkaoda Berlin's `?/default/detail/id=<n>` event pages.
 *
 * The detail page re-renders the **same** event block as the listing — the `<b>`
 * header run, an `h6.heading` title (here without the link) and the body `<p>` — with
 * one difference that is the entire reason it is fetched: the body is **untruncated**,
 * where the listing cuts it off with a `••• weiterlesen…` marker. Everything else
 * (date, category, title, flyer) is a duplicate of the listing and serves only as a
 * fallback, so [ArkaodaWebsiteImporter] merges the two.
 *
 * The venue has no structured field for times, prices, sold-out state or genre — when
 * it mentions a door price or a set time at all it is inside the prose ("€10 Entry on
 * the door", "Live set at 22:00"), which has no reliable delimiter, so those stay null.
 * The one prose value that *is* unambiguous is a labelled ticket link
 * ([arkaodaTicketUrl]). The page's `og:` block is unused — its `og:image` is built with
 * the router's `?/` prefix and 404s.
 *
 * Returns `null` when the page carries no title, which is what an unpublished or
 * deleted id renders (an empty block); the importer then degrades to the listing data.
 *
 * @see ArkaodaFieldMapping for the header/title/type/artist rules shared with the listing.
 * @see ArkaodaOverviewPageScraper for discovery.
 * @see ArkaodaWebsiteImporter for the HTTP fetch orchestrator.
 */
class ArkaodaDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the event
     * block or its title is missing.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl], to derive
     *   the [ScrapedEvent.sourceId], and to resolve the relative flyer link.
     */
    @Suppress("ReturnCount") // Guard clauses for the missing block/title/id are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val excerpt = document.selectFirst("#posts-list .box .excerpt") ?: return null
        val rawTitle = excerpt.selectFirst("h6.heading")?.text()?.trim()
        if (rawTitle.isNullOrBlank()) {
            logger.warn { "arkaoda detail page $sourceUrl has no event title, skipping" }
            return null
        }

        val eventId = EVENT_ID_PATTERN.find(sourceUrl)?.groupValues?.get(1)
        if (eventId == null) {
            logger.warn { "arkaoda detail URL $sourceUrl carries no numeric id, skipping" }
            return null
        }

        val header = parseArkaodaHeader(excerpt)
        val title = arkaodaTitle(rawTitle)
        val eventType = arkaodaEventType(header.category, title)
        val descriptionLines = parseDescriptionLines(excerpt)

        return ScrapedEvent(
            title = title,
            description = descriptionLines.joinToString("\n").takeIf { it.isNotBlank() },
            eventType = eventType,
            // The listing carries the same date; the sentinel defers to it via
            // ArkaodaWebsiteImporter.fillGapsFromOverview when this page omits it.
            eventDate = header.eventDate ?: UNRESOLVED_EVENT_DATE,
            imageUrl = parseImageUrl(document, sourceUrl),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.ARKAODA.sourceIdPrefix}$eventId",
            ticketUrl = arkaodaTicketUrl(descriptionLines),
            artists = arkaodaArtists(title, eventType),
            promoters = arkaodaPromoters(title)
        )
    }

    /**
     * Reads the body `<p>` as its `<br>`-delimited lines, so the venue's paragraph
     * breaks survive instead of being flattened into one run of text by `.text()`.
     * Keeping the lines (rather than joining immediately) is what lets
     * [arkaodaTicketUrl] see the `Tickets:` label on the line above its link. The
     * site's leaked PHP escapes are undone as in the title.
     */
    private fun parseDescriptionLines(excerpt: Element): List<String> =
        excerpt
            .textLinesAt("p")
            .map(::unescapeAddslashes)

    /** Resolves the sidebar flyer, which the theme links relative to the site root. */
    private fun parseImageUrl(
        document: Document,
        sourceUrl: String
    ): String? {
        val src =
            document
                .selectFirst("#sidebar a.highslide img")
                ?.attr("src")
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { resolveUrl(sourceUrl, src) }.getOrNull()
    }

    private companion object {
        /** The numeric event id in a `?/default/detail/id=<n>` URL — see [ArkaodaOverviewPageScraper]. */
        private val EVENT_ID_PATTERN = Regex("""\bid=(\d+)""")
    }
}
