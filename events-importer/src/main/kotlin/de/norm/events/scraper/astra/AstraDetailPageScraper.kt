package de.norm.events.scraper.astra

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.parsePresaleAndBoxOfficePrices
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Astra Kulturhaus event detail pages.
 *
 * The detail page is the **primary data source** for each event. It reuses the
 * same `.event__*` header markup as the overview (parsed via
 * [parseAstraEventBlock]) and adds the fields that only the detail page carries:
 * - promoter(s) (`.promoters__link`)
 * - presale / box-office prices (`.prices .price`)
 * - ticket shop URL (`.purchase-option__button`)
 * - description / artist bio (`.gig__description`, `.detail__description`)
 *
 * The detail page does not render the artist roster, so `artists` is left for
 * the overview page to supply via [AstraWebsiteImporter.fillGapsFromOverview].
 * It may render a `kind` label, but that is the raw per-day value (uncorrected
 * by the overview's festival-day normalization), so the overview type wins in
 * the merge and the detail value is only a fallback.
 *
 * @see AstraOverviewPageScraper for overview parsing (event type, discovery).
 * @see AstraWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.astra-berlin.de/events/2026-05-18-green-lung">Example detail page</a>
 */
class AstraDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent].
     *
     * Scopes parsing to the `main.page-content` container, which holds the
     * single event. Returns `null` if the container or the event title is
     * missing (an unexpected page structure).
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to
     *   derive the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for missing container and title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.selectFirst("main.page-content") ?: document.body()
        val block = parseAstraEventBlock(content, sourceUrl)
        if (block == null) {
            logger.warn { "Detail page at $sourceUrl has no event title, skipping" }
            return null
        }

        val (pricePresale, priceBoxOffice) = parsePresaleAndBoxOfficePrices(content.select(".prices .price"))

        return ScrapedEvent(
            title = block.title,
            subtitle = block.subtitle,
            // The banner leads the description, the way the Lido template stores it.
            description = listOfNotNull(block.notice, parseDescription(content)).joinToString("\n").ifBlank { null },
            eventType = block.eventType,
            // Detail pages always carry the real date; sentinel only if absent
            // (then the overview value is used via fillGapsFromOverview).
            eventDate = block.eventDate ?: UNRESOLVED_EVENT_DATE,
            doorsTime = block.doorsTime,
            startTime = block.startTime,
            imageUrl = block.imageUrl,
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.ASTRA.sourceIdPrefix}${extractEventSlug(sourceUrl)}",
            ticketUrl = content.hrefAt(".purchase-option__button"),
            pricePresale = pricePresale,
            priceBoxOffice = priceBoxOffice,
            soldOut = block.soldOut,
            status = block.status,
            promoters = parsePromoters(content)
        )
    }

    /**
     * Extracts promoter names from the `.promoters__link` anchors, deduplicated
     * while preserving order (the markup repeats them for mobile/desktop layouts).
     */
    private fun parsePromoters(content: Element): List<String> =
        content
            .select(".promoters__link")
            .mapNotNull { it.text().trim().takeIf { name -> name.isNotBlank() } }
            .distinct()

    /**
     * Extracts the event description from the artist bio and detail sections.
     *
     * Paragraphs are gathered from `.gig__description` (per-artist bios) and
     * `.detail__description` (event blurb), deduplicated while preserving order
     * since both layouts can repeat content. Returns `null` when no prose exists.
     */
    private fun parseDescription(content: Element): String? =
        content
            .select(".gig__description p, .detail__description p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
}
