package de.norm.events.scraper.urbanspree

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * Pure HTML parser for one page of Urban Spree's `/program/` listing, server-rendered by MODX
 * (pdoTools' `pdoPage`) into a `#pdopage` grid of `a.card` anchors, nine per page:
 *
 * | Field       | Source                                                          |
 * |-------------|-----------------------------------------------------------------|
 * | date + time | `data-dateStart="YYYY-MM-DD HH:mm:ss"`                          |
 * | event type  | `.card-text.cat` category label ("Concerts", "Exhibitions", …)   |
 * | title       | `.card-text.title` — **CSS-truncated**, so only a fallback      |
 * | price       | `li.price` ("17.00€", or "Free")                                |
 * | image       | `data-imgfeat` — the original upload, not a thumbnail           |
 * | detail page | `href` to `/program/<category>/<slug>.html`                     |
 *
 * The card title is cut with an ellipsis, so [UrbanSpreeDetailPageScraper] supplies the title,
 * promoter and description; the overview owns the machine-readable date and the full-size
 * poster path. URLs go through Jsoup's `abs:` rather than
 * [resolveUrl][de.norm.events.scraper.resolveUrl]: the hrefs are relative
 * (`program/concerts/…`) against a `<base href="https://www.urbanspree.com/">` tag, and resolving
 * against the page URL would double the `program/` segment past page one.
 *
 * @see UrbanSpreeDetailPageScraper for the primary per-event data source.
 * @see UrbanSpreeWebsiteImporter for the paginated fetch orchestrator.
 */
class UrbanSpreeOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every card on one listing page, in page order; the venue sorts descending by date,
     * which [UrbanSpreeWebsiteImporter] relies on to stop. Cards without a parseable
     * `data-dateStart` or detail link are skipped with a warning.
     *
     * @param baseUrl the URL the document was fetched from, for logging only; links resolve
     * through the page's `<base>`.
     * @return one [ScrapedEvent] per parseable card.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the page
        val events =
            document.select(EVENT_CARD_SELECTOR).mapNotNull { card ->
                try {
                    parseCard(card)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Urban Spree card on $baseUrl, skipping" }
                    null
                }
            }
        logger.info { "Found ${events.size} event card(s) on Urban Spree listing $baseUrl" }
        return events
    }

    /** Parses a single `a.card` anchor, or `null` when it carries no usable date or detail link. */
    @Suppress("ReturnCount") // Guard clauses for the unusable-card cases are clearer than nesting
    private fun parseCard(card: Element): ScrapedEvent? {
        val sourceUrl = card.absUrl("href").takeIf { it.isNotBlank() } ?: return null
        val rawDateStart = card.attr(DATE_START_ATTR)
        val startsAt = parseCardDateTime(rawDateStart)
        if (startsAt == null) {
            logger.warn { "Skipping Urban Spree card $sourceUrl: unparseable $DATE_START_ATTR='$rawDateStart'" }
            return null
        }

        val rawTitle = card.textAt(".card-text.title")
        if (rawTitle == null) {
            logger.warn { "Skipping Urban Spree card $sourceUrl: no title" }
            return null
        }

        val priceText = card.textAt("li.price")
        val price = parsePriceValue(priceText)
        // The same billing-note split as the detail page, so the fallback title stays clean; the
        // ellipsis usually cuts the note off.
        val (headline, supportNote) = splitUrbanSpreeBilling(rawTitle)
        return ScrapedEvent(
            // The card title is CSS-truncated ("… + Nico Amara…"); the detail page overrides it.
            title = cleanUrbanSpreeTitle(headline),
            subtitle = supportNote,
            eventType = mapEventType(card.textAt(".card-text.cat"), URBAN_SPREE_CATEGORY_SYNONYMS),
            eventDate = startsAt.toLocalDate(),
            startTime = startsAt.toLocalTime(),
            imageUrl = normalizeAssetUrl(card.absUrl(IMAGE_ATTR)),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.URBAN_SPREE.sourceIdPrefix}${urbanSpreeEventSlug(sourceUrl)}",
            pricePresale = price,
            // "Free" is the venue's own label in the price slot, so the note is pricing-scoped.
            free = detectFree(pricePresale = price, priceNote = priceText),
            status = urbanSpreeStatus(rawTitle)
        )
    }

    /**
     * Parses the card's `YYYY-MM-DD HH:mm:ss` stamp, ISO with a space for the `T`. `null` when
     * blank or malformed.
     */
    private fun parseCardDateTime(raw: String): LocalDateTime? {
        if (raw.isBlank()) return null
        return try {
            LocalDateTime.parse(raw.trim().replace(' ', 'T'))
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private companion object {
        /**
         * The cards inside `#pdopage`, so the "upcoming events" slider's identical markup is not matched.
         */
        private const val EVENT_CARD_SELECTOR = "#pdopage a.card[data-dateStart]"

        /** Machine-readable start stamp on a card, e.g. `2026-12-12 20:00:00`. */
        private const val DATE_START_ATTR = "data-dateStart"

        /** Original (non-thumbnailed) poster path on a card, e.g. `assets/project/urbanspree/mediasource/IMG_1389.JPG`. */
        private const val IMAGE_ATTR = "data-imgfeat"
    }
}

/**
 * The stable identity from the detail URL's path, the category and slug MODX assigns:
 * `…/program/concerts/twin-noir-hinfort-urban-spree,-berlin.html` to
 * `concerts/twin-noir-hinfort-urban-spree,-berlin`. From the URL rather than the truncated,
 * editable title, so the `sourceId` survives an edit; both pages must agree on it.
 */
internal fun urbanSpreeEventSlug(sourceUrl: String): String =
    URI(sourceUrl)
        .path
        .removePrefix(PROGRAM_PATH_PREFIX)
        .removeSuffix(".html")
        .trim('/')

/** Path prefix stripped from a detail URL to leave the `<category>/<slug>` identity. */
private const val PROGRAM_PATH_PREFIX = "/program/"
