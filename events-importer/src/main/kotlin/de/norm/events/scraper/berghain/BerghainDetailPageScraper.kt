package de.norm.events.scraper.berghain

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.net.URI

/**
 * Pure HTML parser for Berghain `/de/event/<id>/` detail pages.
 *
 * The detail page is the enrichment source in the two-page pipeline: it adds the
 * poster image, the ticket-shop link, presale / box-office (`Abendkasse`) prices
 * with an `ausverkauft` sold-out marker, and a prose description that the overview
 * listing lacks. It re-parses the core fields (title, date, times, floor) from the
 * same markup so it is self-sufficient as the merge's primary event, but it
 * deliberately does **not** parse the lineup — the detail page renders artists in a
 * flat running-order that mixes in labels, whereas the overview lists each act in
 * its own span, so [BerghainOverviewPageScraper]'s artists win on merge.
 *
 * All parsing is scoped to the `<main>` content region, excluding the site header, navigation and
 * footer.
 *
 * @see BerghainWebsiteImporter for the fetch orchestration and overview merge.
 */
class BerghainDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a single event detail page.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to
     *   derive its [ScrapedEvent.sourceId].
     * @return the parsed event, or `null` when the page lacks the `<main>`
     *   container, a title, or a parseable date (an unexpected structure).
     */
    @Suppress("ReturnCount") // Guard clauses for the missing container/title/date are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content =
            document.selectFirst("main") ?: run {
                logger.warn { "Berghain detail page at $sourceUrl has no <main> container, skipping" }
                return null
            }

        val title =
            content.textAt("h1") ?: run {
                logger.warn { "Berghain detail page at $sourceUrl has no title, skipping" }
                return null
            }

        val dateLine =
            content.select("p").firstOrNull { parseGermanDate(it.selectFirst("span.font-bold")?.text()) != null }
        val eventDate =
            parseGermanDate(dateLine?.selectFirst("span.font-bold")?.text()) ?: run {
                logger.warn { "Berghain detail page at $sourceUrl has no parseable date, skipping" }
                return null
            }

        val lineText = dateLine?.text().orEmpty()
        val floors = content.select("[data-set-floor] h2").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
        val tickets = parseTickets(content)

        return ScrapedEvent(
            title = title,
            description = parseDescription(content),
            eventType = floorToEventType(floors),
            eventDate = eventDate,
            doorsTime = parseTime(DOORS_PATTERN.find(lineText)?.groupValues?.get(1)),
            startTime = parseTime(START_PATTERN.find(lineText)?.groupValues?.get(1)),
            genre = floorsToGenre(floors),
            imageUrl = content.imgSrcAt("figure img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.BERGHAIN.sourceIdPrefix}${extractEventId(sourceUrl)}",
            ticketUrl = tickets.ticketUrl,
            pricePresale = tickets.presale,
            priceBoxOffice = tickets.boxOffice,
            soldOut = tickets.soldOut
            // Lineup intentionally omitted — the overview page is the authoritative artist source (see class KDoc).
        )
    }

    /** Extracts the numeric event id from the detail URL path (`/de/event/80835/` → `80835`). */
    private fun extractEventId(sourceUrl: String): String = URI(sourceUrl).path.trim('/').substringAfterLast('/')

    /**
     * Parses the "Tickets" block: the ticket-shop link, the presale and box-office
     * (`Abendkasse`) prices, and the sold-out state. A presale line reading
     * "Vorverkauf ausverkauft" yields a null presale price, but the event counts as
     * sold out only when there is no purchasable option left at all — no ticket
     * link and neither price available.
     */
    private fun parseTickets(content: Element): Tickets {
        val block =
            content.select("h2").firstOrNull { it.text().trim().equals(TICKETS_HEADING, ignoreCase = true) }?.parent()
                ?: return Tickets()

        var presale: BigDecimal? = null
        var boxOffice: BigDecimal? = null
        for (line in block.select("p")) {
            val text = line.text().trim()
            if (text.contains(BOX_OFFICE_MARKER, ignoreCase = true)) {
                boxOffice = boxOffice ?: parsePriceValue(text)
            } else {
                presale = presale ?: parsePriceValue(text)
            }
        }

        val ticketUrl = block.hrefAt("a[href]")
        val soldOut =
            block.text().contains(SOLD_OUT_MARKER, ignoreCase = true) &&
                presale == null && boxOffice == null && ticketUrl == null

        return Tickets(ticketUrl = ticketUrl, presale = presale, boxOffice = boxOffice, soldOut = soldOut)
    }

    /** Joins the `.rich-text` description paragraphs, or `null` when the page carries none. */
    private fun parseDescription(content: Element): String? =
        content
            .select(".rich-text")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /** Mirrors [BerghainOverviewPageScraper]'s floor-based typing: Kantine → concert, other floors → party. */
    private fun floorToEventType(floors: List<String>): String? =
        when {
            floors.any { it.contains(KANTINE_MARKER, ignoreCase = true) } -> EventType.CONCERT.name
            floors.isNotEmpty() -> EventType.PARTY.name
            else -> null
        }

    /** Parsed contents of the "Tickets" block. */
    private data class Tickets(
        val ticketUrl: String? = null,
        val presale: BigDecimal? = null,
        val boxOffice: BigDecimal? = null,
        val soldOut: Boolean = false
    )

    companion object {
        /** Floor label identifying the adjacent concert hall (vs. the Berghain building's club floors). */
        private const val KANTINE_MARKER = "Kantine"

        /** Heading text of the ticket/price block. */
        private const val TICKETS_HEADING = "Tickets"

        /** German label marking the box-office (door) price line. */
        private const val BOX_OFFICE_MARKER = "Abendkasse"

        /** German sold-out marker used in the ticket block. */
        private const val SOLD_OUT_MARKER = "ausverkauft"

        /** Doors time in the date line, e.g. "tür 19:00" (German "Tür" = door). */
        private val DOORS_PATTERN = Regex("""tür\s+(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

        /** Show start time in the date line, e.g. "beginn 21:00". */
        private val START_PATTERN = Regex("""beginn\s+(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)
    }
}
