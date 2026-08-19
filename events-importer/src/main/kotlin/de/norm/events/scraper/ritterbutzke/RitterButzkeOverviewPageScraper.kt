package de.norm.events.scraper.ritterbutzke

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.parseGermanShortDate
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Ritter Butzke's `/events` listing page.
 *
 * The page renders the whole programme, unpaginated, as Bootstrap grid cards. Each card holds an
 * `a.event-link` to the `/event/DDMMYY-<Name>` detail page wrapping the poster, a `DD.MM.YY` date
 * and an `h2` title.
 *
 * The **rendered** date is the one read, never the one encoded in the slug: the slug is minted
 * once and keeps the original date when a show moves (`310726-DeeportamentCommunityw-…` renders
 * `04.09.2026`). The slug is used only for the stable [ScrapedEvent.sourceId], so a moved show
 * keeps its identity — which also matters because the club runs several floors and two or three
 * events routinely share a date.
 *
 * @see RitterButzkeDetailPageScraper for the detail-page data (start time, ticket, DJ lineup).
 * @see RitterButzkeWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://club.ritterbutzke.com/events">Ritter Butzke event listing</a>
 */
class RitterButzkeOverviewPageScraper {
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
        val cards = document.select("div:has(> div > a.event-link[href])")
        logger.info { "Found ${cards.size} event card(s) on Ritter Butzke overview" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the import
        return cards.mapNotNull { card ->
            try {
                parseCard(card, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Ritter Butzke event card, skipping" }
                null
            }
        }
    }

    /** Parses a single grid card into a [ScrapedEvent], or `null` when it has no link or title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = card.selectFirst("a.event-link[href]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, "/event/")

        val title = card.textAt("h2")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Ritter Butzke card '$slug' has no title, skipping" }
            return null
        }

        return ScrapedEvent(
            title = title,
            // Every night is a DJ programme; the venue publishes no categories.
            eventType = EventType.PARTY.name,
            // The card's own two-digit-year date — see the class KDoc for why the slug's DDMMYY
            // is deliberately ignored.
            eventDate = parseGermanShortDate(card.textAt(DATE_SELECTOR)) ?: UNRESOLVED_EVENT_DATE,
            imageUrl = card.imgSrcAt("a.event-link img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.RITTER_BUTZKE.sourceIdPrefix}$slug"
        )
    }
}

/**
 * The card's date cell. The venue gives it no class of its own beyond a Bootstrap padding utility,
 * so it is reached through the row that also holds the (robots-disallowed) calendar link — the
 * narrowest stable container available on this template.
 */
private const val DATE_SELECTOR = ".d-flex.flex-row > .pt-1"
