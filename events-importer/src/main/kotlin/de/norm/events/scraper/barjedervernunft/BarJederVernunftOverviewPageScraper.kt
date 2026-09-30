package de.norm.events.scraper.barjedervernunft

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.queryParameter
import de.norm.events.scraper.schemaDate
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaSoldOut
import de.norm.events.scraper.schemaTime
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import de.norm.events.scraper.withQueryParameter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import tools.jackson.databind.JsonNode
import java.net.URI

/**
 * Pure HTML parser for Bar jeder Vernunft's Neos-CMS calendar page (`/de/programm/kalender.html`).
 *
 * One `.card-type-calendar` per **performance date**, so a residency appears once per night —
 * no range to expand. Each card is immediately followed by its own
 * `<script type="application/ld+json">` schema.org `Event`, the **primary source** for every
 * structured field (`startDate`, `image`, `url`, `performer`, `offers.availability`). The card
 * markup supplies only the show's sub-line and the ticket-shop link.
 *
 * A card **without** a JSON-LD sibling is skipped: the rendered date is year-less ("Fr 31.7.")
 * and the start time a German "20 Uhr" label, so no dependable fallback — guessing a year is
 * worse than reporting nothing.
 *
 * `genre`, prices and the untruncated description live on the show page and are merged in
 * afterwards; see [BarJederVernunftShow].
 *
 * @see BarJederVernunftWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.bar-jeder-vernunft.de/de/programm/kalender.html">Bar jeder Vernunft calendar</a>
 */
class BarJederVernunftOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every dated performance from the calendar page. Takes no base URL, unlike the other
     * overview scrapers: every link (the show URL from the JSON-LD, the ticket-shop link on the
     * card) is already absolute.
     *
     * @return one [ScrapedEvent] per calendar card with parseable structured data.
     */
    fun scrape(document: Document): List<ScrapedEvent> {
        val cards = document.select(CARD_SELECTOR)
        logger.info { "Found ${cards.size} calendar card(s) on page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip a single malformed card without aborting the import
        return cards.mapNotNull { card ->
            try {
                parseCard(card)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Bar jeder Vernunft calendar card, skipping" }
                null
            }
        }
    }

    /**
     * The URL of the calendar batch after [document], or null after an empty batch. The page holds
     * the first batch and names the later ones in `data-render-partial-url`, which the site's script
     * requests with `&currentPage=N`. Every batch claims a next page, so an empty one ends the walk.
     */
    fun nextBatchUrl(
        document: Document,
        url: String
    ): String? {
        val current = url.queryParameter(CURRENT_PAGE_PARAMETER)?.toIntOrNull()
        return when {
            document.selectFirst(CARD_SELECTOR) == null -> {
                null
            }

            current != null -> {
                url.withQueryParameter(CURRENT_PAGE_PARAMETER, current + 1)
            }

            else -> {
                document
                    .selectFirst(
                        BATCH_LIST_SELECTOR
                    )?.absUrl(BATCH_URL_ATTRIBUTE)
                    ?.takeIf { it.isNotEmpty() }
                    ?.withQueryParameter(CURRENT_PAGE_PARAMETER, 2)
            }
        }
    }

    /**
     * Parses one card plus its JSON-LD sibling into a [ScrapedEvent], or `null` when the
     * structured data is missing or incomplete.
     *
     * Title and subtitle follow the page's own split: the `performer` (`.event-artist`) names the
     * act or show, `.event-title` its sub-line — exactly how the JSON-LD `name` is assembled
     * ("`<performer> - <sub-line>`"), so the two are kept apart rather than re-split.
     */
    @Suppress("ReturnCount") // Guard clauses per missing required field are clearer than nesting
    private fun parseCard(card: Element): ScrapedEvent? {
        val eventNode = parseJsonLd(card) ?: return null

        val title = eventNode.stringOrNull("performer") ?: card.textAt(".event-artist")
        if (title.isNullOrBlank()) {
            logger.warn { "Calendar card has no performer, skipping" }
            return null
        }

        val sourceUrl = eventNode.stringOrNull("url")?.takeIf { it.startsWith("http") }
        if (sourceUrl == null) {
            logger.warn { "Event '$title' has no show URL, skipping" }
            return null
        }

        val eventDate = eventNode.schemaDate("startDate")
        if (eventDate == null) {
            logger.warn { "Could not parse event date for '$title', skipping" }
            return null
        }

        return ScrapedEvent(
            title = title,
            subtitle = card.textAt(".event-title"),
            // Truncated teaser — the show page supplies the full text and overwrites this. The CMS stores
            // the blurb HTML-escaped and script content is raw text (no entity decoding by Jsoup), so
            // "&amp;" survives into the JSON string.
            description = eventNode.stringOrNull("description")?.let { Parser.unescapeEntities(it, false) },
            eventDate = eventDate,
            startTime = eventNode.schemaTime("startDate"),
            imageUrl = eventNode.schemaImageUrl(),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.BAR_JEDER_VERNUNFT.sourceIdPrefix}$eventDate-${showSlug(sourceUrl)}",
            ticketUrl = card.hrefAt("a[data-ticketing]"),
            soldOut = eventNode.schemaSoldOut()
        )
    }

    /**
     * The schema.org `Event` node from the JSON-LD block following [card], or `null` without such
     * a sibling or when unparseable. Only the **immediate** next sibling: scanning further would
     * let a card missing its block silently adopt the next card's date and title.
     */
    private fun parseJsonLd(card: Element): JsonNode? =
        card
            .nextElementSibling()
            ?.takeIf { it.tagName() == "script" && it.attr("type") == "application/ld+json" }
            ?.let { jsonLdEvents(it.data()).firstOrNull() }

    /**
     * The show's stable identity, the last path segment of its canonical URL
     * (`…/programmuebersicht/oh-what-a-night-frankie-valli-show.html` →
     * `oh-what-a-night-frankie-valli-show`). Combined with the date for the `sourceId`, because
     * the URL alone is shared by every night of a run.
     */
    private fun showSlug(url: String): String =
        URI(url)
            .path
            .substringAfterLast('/')
            .removeSuffix(".html")

    private companion object {
        /**
         * One calendar entry. `card-type-calendar` is the calendar page's own card variant — show
         * pages render their date lists as `card-type-date` — so the selector cannot pick up a card
         * from elsewhere on the site.
         */
        const val CARD_SELECTOR = ".card-type-event.card-type-calendar"

        const val BATCH_LIST_SELECTOR = ".partial-render[data-render-partial-url]"
        const val BATCH_URL_ATTRIBUTE = "data-render-partial-url"
        const val CURRENT_PAGE_PARAMETER = "currentPage"
    }
}
