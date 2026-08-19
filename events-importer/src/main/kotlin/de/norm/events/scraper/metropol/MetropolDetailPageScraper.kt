package de.norm.events.scraper.metropol

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ISO_DATE_LENGTH
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.stripRelocationPrefix
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document

/**
 * Pure HTML parser for Metropol Berlin event detail pages (`/event/<iso-date-slug>`).
 *
 * Each page renders an `.em-event-single` block: an optional `.alert-red` status badge and
 * `.alert-blue` explanatory note, a `.promoter` line (`Trinity Music presents:`), the `h1`
 * headliner, an optional `p.tour` subtitle, an `.event-details` box holding the rendered date,
 * the `Einlass: 19:00 // Beginn: 20:00` time line and the category, an `a.button.ticket`
 * Eventim link, a lazy-loaded poster, and the `.event-text` prose description.
 *
 * The detail page is the primary source for everything except the support acts, which only the
 * overview's `small.support` line carries (see
 * [MetropolWebsiteImporter.fillGapsFromOverview]).
 *
 * @see MetropolOverviewPageScraper for the listing parser (discovery, support acts, fallback).
 * @see MetropolWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://metropol-berlin.de/event/2026-09-05-mucco">Example detail page</a>
 */
class MetropolDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page carries no
     * `h1` title.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive
     *   the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // A guard clause for the missing title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val rawTitle = document.textAt(".em-event-single h1") ?: document.textAt("h1")
        if (rawTitle == null) {
            logger.warn { "Metropol detail page at $sourceUrl has no title, skipping" }
            return null
        }
        val slug = extractEventSlug(sourceUrl, "/event/")
        val timeLine = document.textAt(".event-details .time")

        return ScrapedEvent(
            title = cleanEventTitle(stripRelocationPrefix(rawTitle)),
            subtitle = document.textAt(".event-info p.tour"),
            description = document.textAt(".event-text"),
            eventType = mapEventType(document.textAt(".event-details ul.event-categories")),
            // The slug's ISO prefix is the canonical date; the sentinel lets the overview's
            // rendered German date backstop it via fillGapsFromOverview.
            eventDate = parseIsoDate(slug.take(ISO_DATE_LENGTH)) ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseMetropolTime(labelledTime(timeLine, DOORS_LABEL)),
            startTime = parseMetropolTime(labelledTime(timeLine, START_LABEL)),
            imageUrl = parsePosterUrl(document),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.METROPOL.sourceIdPrefix}$slug",
            ticketUrl = document.hrefAt("a.button.ticket"),
            status = parseStatus(document, rawTitle),
            promoters = listOfNotNull(parsePromoter(document))
        )
    }

    /**
     * Reads the scheduling status from the `.alert-red` badge (`Achtung: Abgesagt!`) and the
     * title's `"Verlegt ins <venue> –"` prefix.
     *
     * The neighbouring `.alert-blue` prose is deliberately **not** fed to [parseEventStatus].
     * It explains a change in either direction, and both spellings contain "verlegt": a show
     * that moved *out* of the house ("vom Metropol ins Bi Nuu verlegt") is genuinely
     * `RELOCATED`, but one that moved *in* ("vom Gretchen ins Metropol verlegt") does take
     * place here and must stay `SCHEDULED`. Only the badge and the title prefix distinguish
     * the two, so only those are read.
     */
    private fun parseStatus(
        document: Document,
        rawTitle: String
    ): String = parseEventStatus("${document.textAt(".alert-red").orEmpty()} $rawTitle")

    /**
     * Reads the promoter from the `.promoter` line, dropping the venue's `"… presents:"`
     * suffix (`"Trinity Music presents:"` → `"Trinity Music"`). Returns `null` when the line is
     * absent or carries nothing but the suffix.
     */
    private fun parsePromoter(document: Document): String? =
        document
            .textAt(".event-info .promoter")
            ?.replace(PRESENTS_SUFFIX, "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * Reads the poster URL from the desktop `.event-image` thumbnail. The theme lazy-loads its
     * images, so `src` holds a base64 placeholder and the real URL lives in `data-src` — the
     * shared `imgSrcAt` helper would reject the placeholder as non-absolute and return `null`.
     * Falls back to the `<noscript>` copy's plain `src`, which carries no placeholder.
     */
    private fun parsePosterUrl(document: Document): String? =
        (
            document.attrAt(".event-image a.thumbnail img[data-src]", "data-src")
                ?: document.attrAt(".event-image noscript img", "src")
        )?.takeIf { it.startsWith("http") }
}

/**
 * Extracts the time following [label] from the detail page's combined time line
 * (`"Einlass: 19:00 // Beginn: 20:00"`, or just `"Beginn: 20:00"` when the venue lists no
 * doors time). Returns `null` when the label is absent.
 */
private fun labelledTime(
    timeLine: String?,
    label: String
): String? = Regex("""$label\s*:\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE).find(timeLine.orEmpty())?.groupValues?.get(1)

/** The venue's doors label on the detail page's time line. */
private const val DOORS_LABEL = "Einlass"

/** The venue's start label on the detail page's time line. */
private const val START_LABEL = "Beginn"

/** The `"… presents:"` suffix the venue appends to every promoter name. */
private val PRESENTS_SUFFIX = Regex("""\s*presents\s*:\s*$""", RegexOption.IGNORE_CASE)
