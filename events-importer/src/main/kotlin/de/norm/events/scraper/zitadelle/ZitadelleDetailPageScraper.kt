package de.norm.events.scraper.zitadelle

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.time.LocalTime

/**
 * Pure HTML parser for a Citadel Music Festival `/event/<YYYY-MM-DD-slug>` detail page.
 *
 * The page adds what a listing card cannot: the `Einlass` doors time, the tour title, the prose
 * description, the ticket-shop link, the presenting media partners, and the fuller account of a
 * changed date. Its facts sit in a `ul.details-list` of `<li><span>Label</span>value` rows.
 *
 * It deliberately supplies **no date**: the page renders it only as long German prose
 * ("Samstag, 15. August 2026") where the listing already carries a machine-readable
 * `time[datetime]`, so the date is left to [ZitadelleWebsiteImporter.fillGapsFromOverview].
 *
 * Three things are specific to this venue:
 *  - **A changed date is stated twice.** An `.abgesagtausverkauft` badge reads `Abgesagt`, and a
 *    separate `.aenderungen` line explains what actually happened ("Wird in die Columbiahalle
 *    verlegt. Tickets behalten ihre Gültigkeit!"). Where that line says the show was *verlegt*, the
 *    truthful status is [EventStatus.RELOCATED], not cancelled — and the line itself is kept at the
 *    head of the description, since the status alone does not say where the show went.
 *  - **The doors time may be `tba`.** A date announced a year ahead states its `Einlass` as "tba";
 *    that must stay `null` rather than becoming a time.
 *  - **The category is a WordPress taxonomy class** on the `<article>` (`event-categories-konzerte`)
 *    rather than rendered text, and only some events carry one. Everything on this site is a
 *    concert, so an event without the class is typed as one anyway.
 *
 * The promoter is likewise only a taxonomy **slug** (`promoters-landstreicher`) with no display
 * name anywhere on the page, so it is left alone; the presenters ("präsentiert von: Flux FM,
 * tip Berlin") are rendered names and are stored instead, as at the Columbia Theater.
 *
 * @see ZitadelleOverviewPageScraper for the listing parser (discovery, title, date, start time).
 * @see ZitadelleWebsiteImporter for the HTTP fetch orchestrator.
 */
class ZitadelleDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page carries no event
     * heading — the marker that the request did not return a real event page.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive the
     *   [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // A guard clause for the missing heading is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val heading = document.textAt(".event-hero h1")?.let { cleanEventTitle(it) }
        if (heading.isNullOrBlank()) {
            logger.warn { "Zitadelle detail page at $sourceUrl has no heading, skipping" }
            return null
        }
        val slug = extractEventSlug(sourceUrl, "/event/")
        val eventType = parseCategory(document)
        val subtitle = document.textAt(".tour-title")
        val changeNote = document.textAt(".aenderungen")

        return ScrapedEvent(
            title = heading,
            subtitle = subtitle,
            description = joinChangeNoteAndDescription(changeNote, document.textAt(".eventnotes")),
            eventType = eventType,
            // The listing owns the date; the sentinel lets it fill this in at the merge.
            eventDate = UNRESOLVED_EVENT_DATE,
            doorsTime = labelledTime(document, DOORS_LABEL),
            startTime = labelledTime(document, START_LABEL),
            imageUrl = document.imgSrcAt("a.event-image img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.ZITADELLE.sourceIdPrefix}$slug",
            ticketUrl = document.hrefAt(".ticket-links a.ticket"),
            status = parseDetailStatus(document, changeNote),
            soldOut = document.textAt(".abgesagtausverkauft")?.equals(SOLD_OUT_BADGE, ignoreCase = true) == true,
            artists = buildArtistsForEventType(heading, subtitle, eventType),
            promoters = document.select(".event-presenters li a").mapNotNull { it.text().takeIf(String::isNotBlank) }
        )
    }

    /**
     * The event's type, taken from the `event-categories-<slug>` class the WordPress taxonomy puts
     * on the `<article>`. Events without one are still concerts — this site programmes nothing else
     * — so [EventType.CONCERT] is the fallback rather than an unstated type.
     */
    private fun parseCategory(document: Document): String {
        val category =
            document
                .selectFirst("article.type-event")
                ?.classNames()
                ?.firstOrNull { it.startsWith(CATEGORY_CLASS_PREFIX) }
                ?.removePrefix(CATEGORY_CLASS_PREFIX)
        return mapEventType(category, EXTRA_CATEGORY_SYNONYMS) ?: EventType.CONCERT.name
    }

    /**
     * The scheduling status. A `.aenderungen` line saying the show was *verlegt* outranks the
     * badge above it, which reads `Abgesagt` for both a cancellation and a move.
     */
    private fun parseDetailStatus(
        document: Document,
        changeNote: String?
    ): String =
        if (changeNote?.contains(RELOCATED_MARKER, ignoreCase = true) == true) {
            EventStatus.RELOCATED.name
        } else {
            parseEventStatus(document.textAt(".abgesagtausverkauft").orEmpty())
        }

    /**
     * Reads the `HH:mm` value of a `ul.details-list` row by its German label, or `null` when the
     * row is absent or states something other than a time (an unannounced doors time reads `tba`).
     */
    private fun labelledTime(
        document: Document,
        label: String
    ): LocalTime? {
        val row =
            document
                .select("ul.details-list li")
                .firstOrNull { it.textAt("span").equals(label, ignoreCase = true) }
        return parseTime(row?.ownText()?.trim())
    }
}

/**
 * Puts the venue's change notice ahead of the event prose, so a relocated show says where it went.
 * Returns whichever part exists when the other does not.
 */
private fun joinChangeNoteAndDescription(
    changeNote: String?,
    description: String?
): String? =
    listOfNotNull(changeNote?.takeIf { it.isNotBlank() }, description?.takeIf { it.isNotBlank() })
        .joinToString("\n\n")
        .takeIf { it.isNotBlank() }

/** The German doors label in the details list. */
private const val DOORS_LABEL = "Einlass"

/** The German start-time label in the details list. */
private const val START_LABEL = "Beginn"

/** The badge text marking a sold-out date; captured as the flag, not as a status. */
private const val SOLD_OUT_BADGE = "Ausverkauft"

/** Prefix of the WordPress event-category class on the `<article>`. */
private const val CATEGORY_CLASS_PREFIX = "event-categories-"

/** Category slugs the shared table does not carry — this site pluralises its one category. */
private val EXTRA_CATEGORY_SYNONYMS = mapOf("konzerte" to EventType.CONCERT.name)
