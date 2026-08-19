package de.norm.events.scraper.zitadelle

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for the Citadel Music Festival's `/events` listing — the programme of the
 * open-air concert series in the Zitadelle Spandau.
 *
 * The site is WordPress with the Events Manager plugin, but the grid is a hand-written child-theme
 * template rather than the plugin's own markup, so each event is one `article.cmf-card` carrying
 * everything the listing needs: an `h3.cmf-title`, a machine-readable `time[datetime]`, a
 * `.cmf-time` start, a `data-status` badge, and a link to `/event/<YYYY-MM-DD-slug>`. The
 * programme is a summer season — under a dozen dates — and is rendered in full with no pagination.
 *
 * The plugin's `event` post type is **not** exposed through the WordPress REST API (only `post`,
 * `page` and `attachment` are), so despite the JSON-first preference of ADR-007 there is no API to
 * read and this parses the HTML.
 *
 * Two details are worth knowing. Each card's `aria-label` ends "– Ausverkauft" **on every event
 * regardless of its actual state**, a broken template string; the `data-status` attribute is the
 * one that tracks reality and is what this reads. And the poster is set as a CSS custom property
 * (`style="--bg: url('…')"`) rather than an `<img>`, so it has to be read out of the inline style —
 * a presentational source used only because the listing offers no other.
 *
 * @see ZitadelleDetailPageScraper for the detail pages (doors, description, tickets, presenters).
 * @see ZitadelleWebsiteImporter for the HTTP fetch orchestrator.
 */
class ZitadelleOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every card on the listing page.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve detail links.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val cards = document.select("article.cmf-card")
        logger.info { "Found ${cards.size} event card(s) on Zitadelle overview" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the import
        return cards.mapNotNull { card ->
            try {
                parseCard(card, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Zitadelle event card, skipping" }
                null
            }
        }
    }

    /** Parses a single card into a [ScrapedEvent], or `null` when it has no link or title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = card.attrAt("a.cmf-link", "href")?.takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, "/event/")

        val title = card.textAt("h3.cmf-title")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Zitadelle card '$slug' has no title, skipping" }
            return null
        }
        val badge = card.attr("data-status")

        return ScrapedEvent(
            title = title,
            // Every event on this site is a concert; the venue's only programme is the series.
            eventType = EventType.CONCERT.name,
            eventDate = card.attrAt("time[datetime]", "datetime")?.let { parseIsoDate(it) } ?: UNRESOLVED_EVENT_DATE,
            startTime = parseTime(card.textAt(".cmf-time")),
            imageUrl = parseBackgroundImageUrl(card.attrAt(".cmf-media", "style")),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.ZITADELLE.sourceIdPrefix}$slug",
            status = parseCardStatus(card, badge),
            soldOut = badge.equals(SOLD_OUT_BADGE, ignoreCase = true),
            artists = buildArtistsForEventType(title, subtitle = null, eventType = EventType.CONCERT.name)
        )
    }

    /**
     * The card's scheduling status.
     *
     * A relocated show carries **both** the `Abgesagt` badge and a separate `.status` marker
     * reading `Verlegt`; the marker is the more specific of the two and wins, because the show is
     * not off — it moved house. The detail page states the same thing more fully and overrides this
     * at the merge.
     */
    private fun parseCardStatus(
        card: Element,
        badge: String
    ): String =
        if (card.textAt(".status")?.contains(RELOCATED_MARKER, ignoreCase = true) == true) {
            EventStatus.RELOCATED.name
        } else {
            parseEventStatus(badge)
        }
}

/**
 * Reads the poster URL out of an inline `style="--bg: url('…')"` custom property, the only place
 * the listing carries one. Returns `null` when the attribute is absent or holds no `url(…)`.
 */
internal fun parseBackgroundImageUrl(style: String?): String? = BACKGROUND_URL_PATTERN.find(style.orEmpty())?.groupValues?.get(1)

/** Matches the URL inside a CSS `url('…')` value, with or without quotes. */
private val BACKGROUND_URL_PATTERN = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")

/** The `data-status` badge marking a sold-out date; captured as the flag, not as a status. */
private const val SOLD_OUT_BADGE = "Ausverkauft"

/** The German word for a relocated show, as the listing's secondary `.status` marker renders it. */
internal const val RELOCATED_MARKER = "verlegt"
