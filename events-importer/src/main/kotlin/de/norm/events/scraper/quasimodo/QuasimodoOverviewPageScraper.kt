package de.norm.events.scraper.quasimodo

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Quasimodo Berlin's `/events` listing page.
 *
 * The page renders the whole season unpaginated as `a.event-item` cards grouped under German
 * month headings. Each card carries **two** date blocks, one per breakpoint, and the mobile one
 * is the useful one: `.event-data.visible-xs .date` holds a complete `DD.MM.YYYY - HH:mm`, so
 * neither the month heading nor the abbreviated desktop `.day`/`.month` pair has to be read.
 *
 * The card is also the only source for the venue's **genre tags** (`.event-tags a` — Blues,
 * Latin Jazz, Neo-Soul), and carries the poster as a CSS `background-image` plus the external
 * ticket-shop link. Each links to `/events/<slug>-<postId>`, whose trailing id keeps the
 * `sourceId` stable across the venue's recurring series.
 *
 * @see QuasimodoDetailPageScraper for the detail-page data (category, promoter, prices, text).
 * @see QuasimodoWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://quasimodo.club/events">Quasimodo event listing</a>
 */
class QuasimodoOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the listing page document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the per-event
     *   detail links.
     * @return a list of [ScrapedEvent] instances, one per card.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        // Parse the enclosing <li>, not the anchor: the genre tags and the ticket button are the
        // anchor's *siblings* inside it, so an anchor-scoped lookup would miss both.
        val items = document.select("ul.events li:has(a.event-item[href])")
        logger.info { "Found ${items.size} event card(s) on Quasimodo overview" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the import
        return items.mapNotNull { item ->
            try {
                parseCard(item, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Quasimodo event card, skipping" }
                null
            }
        }
    }

    /** Parses a single listing `<li>` into a [ScrapedEvent], or `null` when it has no title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = card.selectFirst("a.event-item[href]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, "/events/")

        val title = card.textAt("h4.event-title")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Quasimodo card '$slug' has no title, skipping" }
            return null
        }
        // The mobile date block carries the full "DD.MM.YYYY - HH:mm"; the desktop one abbreviates.
        val dateTime = card.textAt(".event-data.visible-xs .date")
        val genre = card.select(".event-tags a").joinToString(", ") { it.text().trim() }.takeIf { it.isNotBlank() }
        val eventType = inferConcertVenueType(title)

        return ScrapedEvent(
            title = title,
            eventType = eventType,
            eventDate = parseGermanDate(dateTime?.substringBefore(DATE_TIME_SEPARATOR)?.trim()) ?: UNRESOLVED_EVENT_DATE,
            startTime = parseTime(dateTime?.substringAfter(DATE_TIME_SEPARATOR, "")?.trim()),
            imageUrl = parseBackgroundImage(card),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.QUASIMODO.sourceIdPrefix}$slug",
            ticketUrl = card.hrefAt("a.ticket"),
            genre = genre,
            artists = buildArtistsForEventType(title, subtitle = null, eventType = eventType)
        )
    }

    /**
     * Reads the poster from the card's `.event-image` CSS `background-image`. The listing serves a
     * 300×300 thumbnail here; the detail page's full-size original replaces it at merge time.
     */
    private fun parseBackgroundImage(card: Element): String? {
        val style = card.attrAt(".event-image", "style") ?: return null
        return BACKGROUND_IMAGE_PATTERN
            .find(style)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.startsWith("http") }
    }
}

/** Matches the URL inside a CSS `background-image: url(...)` declaration, with or without quotes. */
private val BACKGROUND_IMAGE_PATTERN = Regex("""background-image:\s*url\(['"]?([^'")]+)['"]?\)""", RegexOption.IGNORE_CASE)

/** The separator the mobile date block puts between the date and the start time. */
private const val DATE_TIME_SEPARATOR = " - "
