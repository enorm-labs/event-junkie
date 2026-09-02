package de.norm.events.scraper.huxleys

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.labelledTime
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Huxleys Neue Welt's `/events` listing (overview) page.
 *
 * The page groups every upcoming show under `.month` headings and renders each as an
 * `li.event-item` wrapping a link to its `/event/YYYY-MM-DD-<slug>` detail page. A card carries a
 * `.date` day cell (a bare day number plus a month abbreviation — no year), a `.time` line
 * (`Beginn: 20:00 | Einlass: 19:00`), an `.eventname`, an optional `+ Support:` line, an optional
 * `.anderungen` change note, and — for a sold-out or cancelled show — a status word as a CSS class
 * on the `li` plus a matching `.canceledsoldout` badge.
 *
 * The date comes from the **ISO prefix the venue bakes into every event slug**, not from the
 * rendered `.date` cell, which prints no year at all; the month heading above the card would supply
 * one, but the slug states it unambiguously per event and survives regrouping.
 *
 * The overview is the source for the discovery list plus every field except the ones only the
 * detail page carries (tour name, image, ticket URL, description, genre, promoter). Because
 * [HuxleysWebsiteImporter] falls back to this data when a detail page fails to fetch, each card is
 * parsed as completely as the listing allows.
 *
 * @see HuxleysDetailPageScraper for the detail-page data source.
 * @see HuxleysWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://huxleysneuewelt.de/events">Huxleys Neue Welt events</a>
 */
class HuxleysOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the overview page document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve detail links and build
     *   `sourceId` values.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val cards = document.select("li.event-item:has(a[href])")
        logger.info { "Found ${cards.size} event card(s) on Huxleys overview" }

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

    /** Parses a single `li.event-item` card into a [ScrapedEvent], or `null` when it has no link or title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = card.selectFirst("a[href]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, EVENT_PATH_PREFIX)

        val title = card.textAt(".eventname")?.let(::cleanEventTitle) ?: return null
        val support = card.textAt(".support")
        val times = card.textAt(".time").orEmpty()
        val eventType = inferConcertVenueType(title)

        return ScrapedEvent(
            title = title,
            subtitle = support,
            eventType = eventType,
            // The `.date` cell prints a day and a month abbreviation but no year; the slug's ISO
            // prefix is the only per-card date that states one.
            eventDate = parseIsoDate(slug.take(ISO_DATE_LENGTH)) ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(labelledTime(times, DOORS_LABEL)),
            startTime = parseTime(labelledTime(times, START_LABEL)),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.HUXLEYS.sourceIdPrefix}$slug",
            soldOut = card.hasClass(SOLD_OUT_CLASS),
            status = parseHuxleysStatus(card),
            artists = buildArtistsForEventType(title, support, eventType)
        )
    }
}

/** Path prefix of a Huxleys event permalink, stripped to obtain the `YYYY-MM-DD-<slug>` identity. */
internal const val EVENT_PATH_PREFIX = "/event/"

/** Length of the leading ISO `YYYY-MM-DD` date the venue bakes into every event slug. */
internal const val ISO_DATE_LENGTH = 10

/** The CSS class the theme puts on a sold-out card's `li` (and its badge text). */
private const val SOLD_OUT_CLASS = "Ausverkauft"

/** The German doors label on the card's time line ("… | Einlass: 19:00"). */
private const val DOORS_LABEL = "Einlass"

/** The German start label on the card's time line ("Beginn: 20:00 | …"). */
private const val START_LABEL = "Beginn"

/**
 * Reads a card's scheduling status from all three places the venue expresses one: the status word
 * the theme adds as a CSS class on the `li` (`Ausverkauft` / `Abgesagt`), the matching
 * `.canceledsoldout` badge, and the free-text `.anderungen` change note.
 *
 * The note matters because it is the **only** signal for the two statuses that have no badge: a
 * show moved to another date ("… wurde vom 06.03.2026 auf den 01.10.2026 verschoben") and one moved
 * to another house ("Das Konzert wird ins Hole44 verlegt"). All three are fed to
 * [parseEventStatus] together, whose ordering already prefers a cancellation over a move.
 */
internal fun parseHuxleysStatus(card: Element): String =
    parseEventStatus("${card.className()} ${card.textAt(".canceledsoldout").orEmpty()} ${card.textAt(".anderungen").orEmpty()}")
