package de.norm.events.scraper.barjedervernunft

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HH_MM_LENGTH
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.LocalTime

/**
 * Pure HTML parser for Bar jeder Vernunft's Neos-CMS calendar page
 * (`/de/programm/kalender.html`).
 *
 * The calendar lists one `.card-type-calendar` per **performance date**, so a show
 * running a residency appears once per night — no multi-day range has to be expanded
 * here. Each card is immediately followed by its own
 * `<script type="application/ld+json">` schema.org `Event`, which is the **primary
 * source** for every structured field (`startDate`, `image`, `url`, `performer`,
 * `offers.availability`). The card markup supplies only what the JSON-LD does not
 * carry: the show's sub-line and the ticket-shop link.
 *
 * A card **without** a JSON-LD sibling is skipped: the rendered date block is
 * year-less ("Fr 31.7.") and the start time is a German "20 Uhr" label, so there is no
 * dependable fallback — guessing a year is worse than reporting nothing.
 *
 * `genre`, prices and the untruncated description live on the show page and are merged
 * in afterwards; see [BarJederVernunftShow].
 *
 * @see BarJederVernunftWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.bar-jeder-vernunft.de/de/programm/kalender.html">Bar jeder Vernunft calendar</a>
 */
class BarJederVernunftOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

    /**
     * Parses every dated performance from the calendar page.
     *
     * Takes no base URL, unlike the other overview scrapers: every link this page yields
     * (the show URL from the JSON-LD, the ticket-shop link on the card) is already
     * absolute, so there is nothing to resolve.
     *
     * @return one [ScrapedEvent] per calendar card that carries parseable structured data.
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
     * Parses one calendar card plus its JSON-LD sibling into a [ScrapedEvent], or `null`
     * when the structured data is missing or incomplete.
     *
     * Title and subtitle follow the page's own split: the `performer` (`.event-artist`)
     * names the act or the show, and `.event-title` carries its sub-line — which is
     * exactly how the JSON-LD `name` is assembled ("`<performer> - <sub-line>`"), so the
     * two are kept apart rather than re-splitting the concatenation.
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

        val startDate = eventNode.stringOrNull("startDate")
        val eventDate = startDate?.let { parseIsoDate(it) }
        if (eventDate == null) {
            logger.warn { "Could not parse event date for '$title', skipping" }
            return null
        }

        return ScrapedEvent(
            title = title,
            subtitle = card.textAt(".event-title"),
            // Truncated teaser — the show page supplies the full text and overwrites this.
            // The CMS stores the blurb HTML-escaped, and script content is raw text (no
            // entity decoding by Jsoup), so "&amp;" survives into the JSON string.
            description = eventNode.stringOrNull("description")?.let { Parser.unescapeEntities(it, false) },
            eventDate = eventDate,
            startTime = parseOffsetTime(startDate),
            imageUrl = eventNode.stringOrNull("image")?.takeIf { it.startsWith("http") },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.BAR_JEDER_VERNUNFT.sourceIdPrefix}$eventDate-${showSlug(sourceUrl)}",
            ticketUrl = card.hrefAt("a[data-ticketing]"),
            soldOut = isSoldOut(eventNode)
        )
    }

    /**
     * Parses the JSON-LD block that follows [card] and returns its schema.org `Event`
     * node, or `null` when the card has no such sibling or the block is unparseable.
     *
     * Only the **immediate** next sibling is considered: scanning further (as a venue
     * with one script per card allows) would let a card missing its own block silently
     * adopt the next card's date and title.
     */
    @Suppress(
        "TooGenericExceptionCaught", // A malformed block must degrade to null, never abort the import
        "ReturnCount" // Guard clauses for the missing and non-Event block are clearer than nesting
    )
    private fun parseJsonLd(card: Element): JsonNode? {
        val script =
            card.nextElementSibling()?.takeIf {
                it.tagName() == "script" && it.attr("type") == "application/ld+json"
            } ?: return null

        val root =
            try {
                jsonMapper.readTree(script.data())
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Bar jeder Vernunft JSON-LD block" }
                return null
            }
        return root.takeIf { it.isObject && it.stringOrNull("@type") == "Event" }
    }

    /** Whether the schema.org `offers.availability` marks the date as sold out. */
    private fun isSoldOut(eventNode: JsonNode): Boolean {
        val availability = eventNode.path("offers").stringOrNull("availability").orEmpty()
        return SOLD_OUT_AVAILABILITY.any { availability.endsWith(it, ignoreCase = true) }
    }

    /**
     * Reads the wall-clock start time from the site's `startDate`
     * (`"2026-07-31T20:00:00+0200"`).
     *
     * Not [de.norm.events.scraper.parseIsoTime]: Neos emits a **colon-less** UTC offset,
     * which neither an `HH:mm` parse nor `OffsetDateTime.parse` accepts. The venue is in
     * Berlin and the offset always states local time, so the leading `HH:mm` is taken
     * verbatim.
     */
    private fun parseOffsetTime(dateTimeStr: String): LocalTime? = parseTime(dateTimeStr.substringAfter('T', "").take(HH_MM_LENGTH))

    /**
     * The show's stable identity, taken from the last path segment of its canonical URL
     * (`…/programmuebersicht/oh-what-a-night-frankie-valli-show.html` →
     * `oh-what-a-night-frankie-valli-show`).
     *
     * Combined with the date it forms the `sourceId`, because the URL alone is shared by
     * every night of a run.
     */
    private fun showSlug(url: String): String =
        URI(url)
            .path
            .substringAfterLast('/')
            .removeSuffix(".html")

    private companion object {
        /**
         * One calendar entry. `card-type-calendar` is the calendar page's own card
         * variant — the show pages render their date lists as `card-type-date` — so the
         * selector cannot pick up a card from elsewhere on the site.
         */
        const val CARD_SELECTOR = ".card-type-event.card-type-calendar"

        /** schema.org availability values that mean no tickets are left. */
        val SOLD_OUT_AVAILABILITY = listOf("SoldOut", "OutOfStock")
    }
}
