package de.norm.events.scraper.quasimodo

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.detailTableCell
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Quasimodo Berlin event detail pages (`/events/<slug>-<postId>`).
 *
 * Each page adds what the listing card cannot carry: the prose `.description`, the
 * `… präsentiert:` `.promoter`, the full-size poster, and a `<table>` of `Beginn` / `Einlass` /
 * `Vorverkauf` / `Tageskasse` rows — a real presale *and* box-office price.
 *
 * It is also the only page carrying the **category**, as an `event-categories-<slug>` class on
 * its `<article>`. The venue marks its DJ nights `party` and leaves most concerts untagged, and a
 * night can carry both (`Disco Inferno` is `concerts party`) — so `party` wins, and an untagged
 * event falls back to title inference rather than being defaulted to `OTHER`.
 *
 * @see QuasimodoOverviewPageScraper for the listing parser (discovery, date, genre, thumbnail).
 * @see QuasimodoWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://quasimodo.club/events/otis-kane-7410">Example detail page</a>
 */
class QuasimodoDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page carries no title.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive the
     *   [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // A guard clause for the missing title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val rawTitle = document.textAt(".information h1") ?: document.textAt("h1")
        if (rawTitle == null) {
            logger.warn { "Quasimodo detail page at $sourceUrl has no title, skipping" }
            return null
        }
        val slug = extractEventSlug(sourceUrl, "/events/")
        val title = cleanEventTitle(rawTitle)
        val eventType = parseCategory(document) ?: inferConcertVenueType(title)
        val presale = document.detailTableCell(PRESALE_LABEL)

        return ScrapedEvent(
            title = title,
            description = document.textAt(".panel-collapse.description"),
            eventType = eventType,
            // The listing's mobile block is the date source; the sentinel lets it backstop this page.
            eventDate = UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(document.detailTableCell(DOORS_LABEL)),
            startTime = parseTime(document.detailTableCell(START_LABEL)),
            imageUrl = document.hrefAt(".event-image a"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.QUASIMODO.sourceIdPrefix}$slug",
            ticketUrl = document.hrefAt("a.ticket"),
            pricePresale = parsePriceValue(presale),
            priceBoxOffice = parsePriceValue(document.detailTableCell(BOX_OFFICE_LABEL)),
            // The presale is written "ab 30€ (zzgl. Gebühr)"; the numeric field cannot carry the
            // "from" or the booking-fee caveat, so the venue's own wording is kept alongside it.
            priceNote = presale,
            genre = document.select(".tags a").joinToString(", ") { it.text().trim() }.takeIf { it.isNotBlank() },
            promoters = listOfNotNull(parsePromoter(document)),
            artists = buildArtistsForEventType(title, subtitle = null, eventType = eventType)
        )
    }

    /**
     * Maps the `event-categories-<slug>` class on the page's `<article>` to an [EventType], or
     * `null` when the venue tagged the event with no category at all.
     *
     * `party` is checked first because a DJ night can also be filed under `concerts`, and the
     * party reading is the one that keeps its event name out of the artist list.
     */
    private fun parseCategory(document: Document): String? {
        val classes = document.selectFirst("article.type-event")?.classNames().orEmpty()
        return when {
            classes.contains("event-categories-party") -> EventType.PARTY.name
            classes.contains("event-categories-wuehlmaeuse") -> EventType.SHOW.name
            classes.contains("event-categories-concerts") -> EventType.CONCERT.name
            else -> null
        }
    }

    /**
     * Reads the promoter from the `.promoter` line, dropping the venue's `"… präsentiert:"`
     * suffix (`"FKP Scorpio präsentiert:"` → `"FKP Scorpio"`). Returns `null` when the line is
     * absent — the venue's own in-house nights name no promoter.
     */
    private fun parsePromoter(document: Document): String? =
        document
            .textAt(".promoter")
            ?.replace(PRESENTS_SUFFIX, "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}

/** The `"… präsentiert:"` suffix the venue appends to every promoter name. */
private val PRESENTS_SUFFIX = Regex("""\s*präsentiert\s*:\s*$""", RegexOption.IGNORE_CASE)

/** Label of the detail table's doors row. */
private const val DOORS_LABEL = "Einlass"

/** Label of the detail table's start row. */
private const val START_LABEL = "Beginn"

/** Label of the detail table's presale-price row. */
private const val PRESALE_LABEL = "Vorverkauf"

/** Label of the detail table's box-office-price row. */
private const val BOX_OFFICE_LABEL = "Tageskasse"
