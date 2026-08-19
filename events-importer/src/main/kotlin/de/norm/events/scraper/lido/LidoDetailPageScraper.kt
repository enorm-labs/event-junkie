package de.norm.events.scraper.lido

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.parsePresaleAndBoxOfficePrices
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Lido Berlin event detail pages.
 *
 * The detail page reuses the same `event-ticket__*` header markup as the overview
 * (parsed via [parseLidoEventBlock]) and adds the fields the overview lacks:
 * - description / artist bio (`.gig__description`, `.event-description__text`)
 * - presale / box-office prices (`.event-purchase__prices .price`, classified by
 *   [parsePresaleAndBoxOfficePrices], the per-`.price` parser shared with Astra)
 * - ticket shop URL (`.purchase-option__button`)
 * - event/artist image (`img.gig__preview`)
 *
 * The detail header carries no `data-realdate` and no status badge, so the date,
 * sold-out flag, status, and the artist roster are supplied by the overview page
 * via [LidoWebsiteImporter.fillGapsFromOverview].
 *
 * @see LidoOverviewPageScraper for overview parsing (date, type, status, artists).
 * @see LidoWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.lido-berlin.de/events/2026-06-15-sorry">Example detail page</a>
 */
class LidoDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent].
     *
     * Returns `null` if the event header/title is missing (an unexpected page
     * structure).
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to
     *   derive the [ScrapedEvent.sourceId].
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.body()
        val block = parseLidoEventBlock(content, sourceUrl)
        if (block == null) {
            logger.warn { "Detail page at $sourceUrl has no event title, skipping" }
            return null
        }

        val (pricePresale, priceBoxOffice) = parsePresaleAndBoxOfficePrices(content.select(".event-purchase__prices .price"))

        return ScrapedEvent(
            title = block.title,
            subtitle = block.subtitle,
            description = parseDescription(content),
            eventType = block.eventType,
            // The detail header carries no date; the overview supplies it via fillGapsFromOverview.
            eventDate = block.eventDate ?: UNRESOLVED_EVENT_DATE,
            doorsTime = block.doorsTime,
            startTime = block.startTime,
            imageUrl = content.imgSrcAt("img.gig__preview"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.LIDO.sourceIdPrefix}${extractEventSlug(sourceUrl)}",
            ticketUrl = content.hrefAt(".purchase-option__button"),
            pricePresale = pricePresale,
            priceBoxOffice = priceBoxOffice,
            soldOut = block.soldOut,
            status = block.status,
            promoters = block.promoters
        )
    }

    /**
     * Extracts the event description from the artist bio and event-blurb sections.
     *
     * Paragraphs are gathered from `.gig__description` (per-artist bios) and
     * `.event-description__text` (event blurb), deduplicated while preserving
     * order. Returns `null` when no prose exists.
     */
    private fun parseDescription(content: Element): String? =
        content
            .select(".gig__description p, .event-description__text p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
}
