package de.norm.events.scraper.mikropol

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ISO_DATE_LENGTH
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stripRelocationPrefix
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Mikropol Berlin's Events-Manager `/events/` listing (overview) page.
 *
 * The `/events/` page groups every upcoming show under `<h2 class="event-month">` headings
 * and renders each as an `<a class="event">` card linking to its `/event/<date-slug>/`
 * detail page. A card carries a `.date` line (weekday + `DD.MM.YYYY`), a `.time` block
 * (`.start` / `.doors` spans), an `.eventname`, an optional `.support` line, and — for a
 * sold-out or cancelled show — an `Ausverkauft` / `Abgesagt` CSS class on the anchor. A
 * relocated show is not flagged with a class; instead its title opens with a
 * "verlegt in den … –" note (stripped by [stripRelocationPrefix]).
 *
 * The overview is the source for the event discovery list plus every field except the ones
 * only the detail page carries (description, image, ticket URL). Because
 * [MikropolWebsiteImporter] falls back to this data when a detail page fails to fetch, each
 * event is parsed as completely as the listing allows. The date is read from the ISO
 * `YYYY-MM-DD` prefix the venue bakes into every event slug — a canonical identifier cleaner
 * than the German `DD.MM.YYYY` rendering — falling back to the `.date` line.
 *
 * @see MikropolDetailPageScraper for the detail-page data source (description, image, ticket).
 * @see MikropolWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://mikropol-berlin.de/events/">Mikropol event listing</a>
 */
class MikropolOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the overview page document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve relative
     *   detail links and build `sourceId` values.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val cards = document.select("a.event[href]")
        logger.info { "Found ${cards.size} event card(s) on Mikropol overview" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the whole import
        return cards.mapNotNull { card ->
            try {
                parseCard(card, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse event card, skipping" }
                null
            }
        }
    }

    /** Parses a single `a.event` card into a [ScrapedEvent], or `null` when it has no title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = card.attr("href").takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, "/event/")

        val rawTitle = card.textAt("span.eventname") ?: return null
        val title = cleanEventTitle(stripRelocationPrefix(rawTitle))
        val support = card.textAt("span.support")

        val eventType = inferConcertVenueType(title)
        return ScrapedEvent(
            title = title,
            subtitle = support,
            eventType = eventType,
            // Every slug is prefixed with the ISO event date; the `.date` line and sentinel are fallbacks.
            eventDate =
                parseIsoDate(slug.take(ISO_DATE_LENGTH))
                    ?: parseGermanDate(card.selectFirst("div.date")?.ownText())
                    ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(card.textAt("div.time .doors span")),
            startTime = parseTime(card.textAt("div.time .start span")),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.MIKROPOL.sourceIdPrefix}$slug",
            // Sold-out and cancelled are CSS classes on the anchor; a relocation lives in the title.
            soldOut = card.hasClass(SOLD_OUT_CLASS),
            status = parseEventStatus("${card.className()} $rawTitle"),
            artists = buildArtistsForEventType(title, support, eventType)
        )
    }

    private companion object {
        /** The Events-Manager sold-out class the theme adds to a card's anchor. */
        private const val SOLD_OUT_CLASS = "Ausverkauft"
    }
}
