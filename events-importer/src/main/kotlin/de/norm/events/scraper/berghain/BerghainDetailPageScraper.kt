package de.norm.events.scraper.berghain

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
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
import java.time.Instant

/**
 * Pure HTML parser for Berghain `/de/event/<id>/` detail pages.
 *
 * The enrichment source: poster image, ticket-shop link, presale / box-office (`Abendkasse`)
 * prices with an `ausverkauft` marker, a prose description the listing lacks, and the running
 * order's set times. It re-parses the core fields (title, date, times, floor) from the same
 * markup so it is self-sufficient as the merge's primary event. Its artists carry only the set
 * times: the lineup itself — roles, `Live` markers, which acts are billed — is
 * [BerghainOverviewPageScraper]'s, and the merge attaches these times to it.
 *
 * All parsing is scoped to `<main>`, excluding header, navigation and footer.
 *
 * @see BerghainWebsiteImporter for the fetch orchestration and overview merge.
 */
class BerghainDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a single event detail page.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and its [ScrapedEvent.sourceId].
     * @return the parsed event, or `null` without `<main>`, a title, or a parseable date.
     */
    @Suppress("ReturnCount") // Guard clauses for the missing container/title/date are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content =
            document.selectFirst("main") ?: run {
                logger.warn { "Berghain detail page has no <main> container, skipping" }
                return null
            }

        val title =
            content.textAt("h1") ?: run {
                logger.warn { "Berghain detail page has no title, skipping" }
                return null
            }

        val dateLine =
            content.select("p").firstOrNull { parseGermanDate(it.selectFirst("span.font-bold")?.text()) != null }
        val eventDate =
            parseGermanDate(dateLine?.selectFirst("span.font-bold")?.text()) ?: run {
                logger.warn { "Berghain detail page has no parseable date, skipping" }
                return null
            }

        val lineText = dateLine?.text().orEmpty()
        val floors = content.select("[data-set-floor] h2").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
        val tickets = parseTickets(content)

        return ScrapedEvent(
            title = title,
            description = parseDescription(content),
            eventType = floorsToEventType(floors),
            eventDate = eventDate,
            doorsTime = parseTime(BERGHAIN_DOORS_PATTERN.find(lineText)?.groupValues?.get(1)),
            startTime = parseTime(BERGHAIN_START_PATTERN.find(lineText)?.groupValues?.get(1)),
            genre = floorsToGenre(floors),
            imageUrl = content.imgSrcAt("figure img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.BERGHAIN.sourceIdPrefix}${extractEventId(sourceUrl)}",
            ticketUrl = tickets.ticketUrl,
            pricePresale = tickets.presale,
            priceBoxOffice = tickets.boxOffice,
            soldOut = tickets.soldOut,
            artists = parseRunningOrder(content),
            promoters = parsePromoters(content)
        )
    }

    /**
     * The organiser line a hired night ends with, `Eine Veranstaltung von Dynamite Konzerte` (or the
     * English `An event by …`), split into co-promoters at `, `, `&`, `und` and `and`. The club's own
     * nights carry no such line.
     */
    private fun parsePromoters(content: Element): List<String> =
        content
            .select("p")
            .firstNotNullOfOrNull { ORGANISER_LINE.matchEntire(it.text().trim())?.groupValues?.get(1) }
            ?.split(CO_PROMOTER_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /**
     * Every act of the running order with its set's start and end (#2002), or none before the venue
     * publishes it. Until then the page lists the same slots without `data-set-item-start`, which the
     * programme's lineup already covers. Each `[data-set-floor]` block is a floor named by its `<h2>`;
     * a slot's performers are the own text of its bold name span, whose nested `Live` marker and
     * label span are not part of the name:
     *
     * ```html
     * <li data-set-item data-set-item-start="2026-09-27T04:30:00+02:00" data-set-item-end="2026-09-27T08:30:00+02:00">
     *   <div class="running-order-set__info"><span class="font-bold">Colin Benders
     *     <span data-set-item-live>Live</span><span class="lowercase">Hiss & Hertz</span></span></div>
     * </li>
     * ```
     */
    private fun parseRunningOrder(content: Element): List<ScrapedArtist> =
        content.select("[data-set-floor]").flatMap { floor ->
            val stage = floor.textAt("h2")
            floor.select("li[data-set-item-start]").flatMap { slot ->
                val start = parseInstant(slot.attr("data-set-item-start")) ?: return@flatMap emptyList()
                val end = parseInstant(slot.attr("data-set-item-end"))
                splitSlot(slot.selectFirst(SLOT_NAME_SELECTOR)?.ownText().orEmpty())
                    .map { ScrapedArtist(name = it, stage = stage, setStart = start, setEnd = end) }
            }
        }

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    /** The numeric event id from the detail URL path (`/de/event/80835/` → `80835`). */
    private fun extractEventId(sourceUrl: String): String = URI(sourceUrl).path.trim('/').substringAfterLast('/')

    /**
     * The "Tickets" block: ticket-shop link, presale and box-office (`Abendkasse`) prices, sold-out
     * state. "Vorverkauf ausverkauft" yields a null presale price, but the event is sold out only
     * with no purchasable option left — no ticket link and neither price. The CMS prints
     * `0,00€ Abendkasse` for a door price nobody set, so a zero beside a paid presale is dropped
     * rather than stored as a free door (#1589); a zero with no paid presale is how a free night prints.
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

        val doorUnset = boxOffice?.signum() == 0 && (presale?.signum() ?: 0) > 0
        val ticketUrl = block.hrefAt("a[href]")
        val soldOut =
            block.text().contains(SOLD_OUT_MARKER, ignoreCase = true) &&
                presale == null && boxOffice == null && ticketUrl == null

        return Tickets(ticketUrl = ticketUrl, presale = presale, boxOffice = boxOffice.takeUnless { doorUnset }, soldOut = soldOut)
    }

    /** The `.rich-text` description paragraphs joined, or `null`. */
    private fun parseDescription(content: Element): String? =
        content
            .select(".rich-text")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /** Parsed contents of the "Tickets" block. */
    private data class Tickets(
        val ticketUrl: String? = null,
        val presale: BigDecimal? = null,
        val boxOffice: BigDecimal? = null,
        val soldOut: Boolean = false
    )

    companion object {
        /** Heading text of the ticket/price block. */
        private const val TICKETS_HEADING = "Tickets"

        /** German label marking the box-office (door) price line. */
        private const val BOX_OFFICE_MARKER = "Abendkasse"

        /** A running-order slot's name span; its own text is the performers. */
        private const val SLOT_NAME_SELECTOR = ".running-order-set__info span.font-bold"

        /** German sold-out marker used in the ticket block. */
        private const val SOLD_OUT_MARKER = "ausverkauft"
    }
}

/** The organiser line: `Eine Veranstaltung von Sumpfjungs`, `An event by …`. */
private val ORGANISER_LINE = Regex("""(?:eine\s+veranstaltung\s+von|an\s+event\s+by)\s+(.+?)\.?""", RegexOption.IGNORE_CASE)

/** What joins co-promoters in the organiser line. */
private val CO_PROMOTER_SEPARATOR = Regex("""\s*(?:,|&|\bund\b|\band\b)\s*""", RegexOption.IGNORE_CASE)
