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
 * Pure HTML parser for arkaoda Berlin's `?/default/detail/id=<n>` pages. The page re-renders the
 * same event block as the listing (the `<b>` header run, an `h6.heading` title, the body `<p>`)
 * with one difference that is the reason it is fetched: the body is untruncated, where the
 * listing cuts it at a `••• weiterlesen…` marker. Everything else duplicates the listing and
 * serves as fallback ([ArkaodaWebsiteImporter] merges the two).
 *
 * The venue has no structured field for times, prices, sold-out state or genre; a door price
 * or set time appears only inside the prose ("€10 Entry on the door", "Live set at 22:00") with
 * no reliable delimiter, so those stay null. The one unambiguous prose value is a labelled
 * ticket link ([arkaodaTicketUrl]). The `og:` block is unused: its `og:image` carries the
 * router's `?/` prefix and 404s. Returns `null` when the page has no title, what an unpublished
 * or deleted id renders; the importer then degrades to the listing.
 *
 * @see ArkaodaFieldMapping for the header/title/type/artist rules shared with the listing.
 * @see ArkaodaOverviewPageScraper for discovery.
 * @see ArkaodaWebsiteImporter for the HTTP fetch orchestrator.
 */
class ArkaodaDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` when the block or its title is missing.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl], the [ScrapedEvent.sourceId]
     * source, and the base for the relative flyer link.
     */
    @Suppress("ReturnCount") // Guard clauses for the missing block/title/id are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val excerpt = document.selectFirst("#posts-list .box .excerpt") ?: return null
        val rawTitle = excerpt.selectFirst("h6.heading")?.text()?.trim()
        if (rawTitle.isNullOrBlank()) {
            logger.warn { "arkaoda detail page has no event title, skipping" }
            return null
        }

        val eventId = EVENT_ID_PATTERN.find(sourceUrl)?.groupValues?.get(1)
        if (eventId == null) {
            logger.warn { "arkaoda detail URL carries no numeric id, skipping" }
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
            // The listing carries the same date; the sentinel defers to it via fillGapsFromOverview.
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
     * Reads the body `<p>` as its `<br>`-delimited lines, so the venue's paragraph breaks survive
     * and [arkaodaTicketUrl] can see the `Tickets:` label on the line above its link. Leaked PHP
     * escapes are undone as in the title.
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
