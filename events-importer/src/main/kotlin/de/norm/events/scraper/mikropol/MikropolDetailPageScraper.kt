package de.norm.events.scraper.mikropol

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ISO_DATE_LENGTH
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.labelledTime
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stripRelocationPrefix
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document

/**
 * Pure HTML parser for Mikropol Berlin event detail pages (`/event/<date-slug>/`).
 *
 * Each detail page renders a `.single-event` block: an `h1.entry-title`, an optional
 * `h2.support` line, an inline `.event-details` box (`DD.MM.YYYY` date plus `Beginn` /
 * `Einlass` times), a `.ticket-links` Eventim button, a `a.event-image` poster, and an
 * `.eventnotes` description. A sold-out / cancelled show carries a `.canceledsoldout`
 * badge (`Ausverkauft` / `Abgesagt`); a relocated show opens its title with a
 * "verlegt in den … –" note. The theme embeds no schema.org JSON-LD.
 *
 * The detail page is the source for the fields the overview lacks — description, image,
 * and ticket URL — and carries the shared fields (date, times, status) too, so a
 * successful fetch yields a complete event; the overview only fills gaps (and stands in
 * entirely when the detail fetch fails) via [MikropolWebsiteImporter.fillGapsFromOverview].
 *
 * @see MikropolOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see MikropolWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://mikropol-berlin.de/event/2026-07-14-house-of-protection/">Example detail page</a>
 */
class MikropolDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page has no
     * event title (an unexpected structure).
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive the
     *   [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clause for the missing title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.body()

        val rawTitle = content.textAt("h1.entry-title")
        if (rawTitle == null) {
            logger.warn { "Detail page at $sourceUrl has no event title, skipping" }
            return null
        }
        val title = cleanEventTitle(stripRelocationPrefix(rawTitle))

        val slug = extractEventSlug(sourceUrl, "/event/")
        val support = content.textAt("h2.support")
        val details = content.textAt("div.event-details").orEmpty()
        val statusBadge = content.textAt("div.canceledsoldout").orEmpty()

        val eventType = inferConcertVenueType(title)
        return ScrapedEvent(
            title = title,
            subtitle = support,
            description = content.textAt("div.eventnotes"),
            eventType = eventType,
            // Prefer the slug's ISO date prefix, then the German `.eventdates` rendering.
            eventDate =
                parseIsoDate(slug.take(ISO_DATE_LENGTH))
                    ?: parseGermanDate(content.textAt("span.eventdates"))
                    ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(labelledTime(details, "Einlass")),
            startTime = parseTime(labelledTime(details, "Beginn")),
            imageUrl =
                content.hrefAt("a.event-image")
                    ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.MIKROPOL.sourceIdPrefix}$slug",
            ticketUrl = content.hrefAt("div.ticket-links a.ticket"),
            // Sold-out and cancelled render in the `.canceledsoldout` badge; a relocation lives in the title.
            soldOut = statusBadge.contains(SOLD_OUT_TEXT, ignoreCase = true),
            status = parseEventStatus("$statusBadge $rawTitle"),
            artists = buildArtistsForEventType(title, support, eventType)
        )
    }

    private companion object {
        /** The Events-Manager sold-out badge text (`Ausverkauft`). */
        private const val SOLD_OUT_TEXT = "ausverkauft"
    }
}
