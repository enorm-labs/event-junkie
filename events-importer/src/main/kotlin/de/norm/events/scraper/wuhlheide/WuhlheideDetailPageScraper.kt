package de.norm.events.scraper.wuhlheide

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal

/**
 * Pure HTML parser for Parkbühne Wuhlheide event detail pages (`/programm/<act>/YYYY-MM-DD`).
 *
 * Each page renders a `.details` block: an `h1` act, an `h3`, a `<table>` of `Datum` / `Einlass`
 * / `Beginn` / `Preis` rows, a ticket button, and — further down — a `.promoter` block naming the
 * `Veranstalter`. This is the only source for the times, the price and the promoter; the listing
 * carries none of them.
 *
 * Two quirks. The `h3` is **not** reliably a tour name — a show can put an admin notice there
 * ("Bitte die Altersbeschränkungen beachten:") — so the subtitle is deliberately left to the
 * listing (see [WuhlheideWebsiteImporter.fillGapsFromOverview]). And a sold-out show omits both
 * the `Preis` row and the ticket link entirely, so both are simply absent rather than zero.
 *
 * @see WuhlheideOverviewPageScraper for the listing parser (discovery, date, subtitle, sold-out).
 * @see WuhlheideWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.wuhlheide.de/programm/alligatoah/2026-08-01">Example detail page</a>
 */
class WuhlheideDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page carries no title.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive the
     *   [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // A guard clause for the missing title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val rawTitle = document.textAt(".details h1")
        if (rawTitle == null) {
            logger.warn { "Parkbühne Wuhlheide detail page at $sourceUrl has no title, skipping" }
            return null
        }
        val slug = extractEventSlug(sourceUrl, "/programm/")
        val title = cleanEventTitle(rawTitle)

        return ScrapedEvent(
            title = title,
            eventType = inferConcertVenueType(title),
            eventDate = parseIsoDate(slug.substringAfterLast('/')) ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(labelledCell(document, DOORS_LABEL)),
            startTime = parseTime(labelledCell(document, START_LABEL)),
            imageUrl = document.imgSrcAt(".general img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.WUHLHEIDE.sourceIdPrefix}$slug",
            // Anchored on the ticket icon: the sibling share buttons are also target="_blank".
            ticketUrl = document.hrefAt(".details .buttons a.button:has(i.fa-ticket)"),
            pricePresale = parsePrice(labelledCell(document, PRICE_LABEL)),
            promoters = listOfNotNull(document.textAt(".promoter a"))
        )
    }

    /**
     * Reads the value cell of the detail table row whose label cell is [label] (`Einlass:`,
     * `Beginn:`, `Preis:`). Returns `null` when the row is absent — a sold-out show omits the
     * price row entirely.
     */
    private fun labelledCell(
        document: Document,
        label: String
    ): String? {
        val row = document.select(".details table tr").firstOrNull { isLabelRow(it, label) } ?: return null
        val value =
            row
                .select("td")
                .getOrNull(1)
                ?.text()
                ?.trim()
        return value?.takeIf { it.isNotBlank() }
    }

    /** Whether [row]'s first cell is the `"<label>:"` header of the detail table. */
    private fun isLabelRow(
        row: Element,
        label: String
    ): Boolean = row.textAt("td").equals("$label:", ignoreCase = true)

    /**
     * Parses the venue's `"69,90 EUR"` price. The currency is spelled out rather than written as
     * `€`, so the shared `€`-anchored [parsePriceValue][de.norm.events.scraper.parsePriceValue]
     * does not match it; the German decimal comma is normalised before conversion. Returns `null`
     * for an absent or unparseable price.
     */
    private fun parsePrice(text: String?): BigDecimal? {
        val match = PRICE_PATTERN.find(text.orEmpty()) ?: return null
        return match.groupValues[1].replace(',', '.').toBigDecimalOrNull()
    }
}

/** Matches the venue's `NN,NN EUR` price, whose currency is spelled out rather than `€`. */
private val PRICE_PATTERN = Regex("""(\d+(?:[.,]\d{1,2})?)\s*EUR""", RegexOption.IGNORE_CASE)

/** Label of the detail table's doors row. */
private const val DOORS_LABEL = "Einlass"

/** Label of the detail table's start row. */
private const val START_LABEL = "Beginn"

/** Label of the detail table's price row. */
private const val PRICE_LABEL = "Preis"
