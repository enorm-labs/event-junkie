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
 * Pure HTML parser for Astra Kulturhaus event detail pages — the **primary data source**.
 *
 * Reuses the overview's `.event__*` header markup ([parseAstraEventBlock]) and adds the
 * detail-only fields: promoter(s) (`.promoters__link`), presale / box-office prices
 * (`.prices .price`), ticket shop URL (`.purchase-option__button`), description / artist bio
 * (`.gig__description`, `.detail__description`).
 *
 * No artist roster here, so `artists` is left to the overview via
 * [AstraWebsiteImporter.fillGapsFromOverview]. A `kind` label may render, but as the raw
 * per-day value without the overview's festival-day normalization, so the overview type wins
 * and the detail value is only a fallback.
 *
 * @see AstraOverviewPageScraper for overview parsing (event type, discovery).
 * @see AstraWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.astra-berlin.de/events/2026-05-18-green-lung">Example detail page</a>
 */
class AstraDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], scoped to `main.page-content` (the single
     * event). `null` when the container or the title is missing.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for missing container and title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.selectFirst("main.page-content") ?: document.body()
        val block = parseAstraEventBlock(content, sourceUrl)
        if (block == null) {
            logger.warn { "Detail page has no event title, skipping" }
            return null
        }

        val (pricePresale, priceBoxOffice) = parsePresaleAndBoxOfficePrices(content.select(".prices .price"))

        return ScrapedEvent(
            title = block.title,
            subtitle = block.subtitle,
            // The banner leads the description, the way the Lido template stores it.
            description = listOfNotNull(block.notice, parseDescription(content)).joinToString("\n").ifBlank { null },
            eventType = block.eventType,
            // Detail pages always carry the real date; sentinel only if absent (then fillGapsFromOverview).
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
            promoters = parsePromoters(content),
            promoterWebsites = parsePromoterWebsites(content)
        )
    }

    /**
     * Promoter names from the `.promoters__link` anchors, deduplicated in order (the markup repeats
     * them for mobile/desktop layouts).
     */
    private fun parsePromoters(content: Element): List<String> =
        content
            .select(".promoters__link")
            .mapNotNull { it.text().trim().takeIf { name -> name.isNotBlank() } }
            .distinct()

    /** Each promoter anchor's `href`, where it links out (#1319). */
    private fun parsePromoterWebsites(content: Element): Map<String, String> =
        content
            .select(".promoters__link")
            .mapNotNull { link ->
                val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = link.absUrl("href").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                name to url
            }.toMap()

    /**
     * Description from `.gig__description` (per-artist bios) and `.detail__description` (event
     * blurb), deduplicated in order since both layouts can repeat content. `null` without prose.
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
