package de.norm.events.scraper.aeg

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.net.URI
import java.time.LocalTime

/**
 * Pure HTML parser for the AEG venues' event detail pages
 * (`/events/detail/<slug>/<YYYY-MM-DD-HHMM>`).
 *
 * The page adds the three things a listing row cannot carry: the `Einlass` doors time, the prose
 * description, and the external ticket-shop link.
 *
 * It deliberately supplies **no date and no usable title**. Its `h1.summary` appends the venue
 * ("JONY **live in der Uber Eats Music Hall**"), so the listing's cleaner act name is kept; and the
 * listing already assembled the date from its own span group. Both are left to
 * [AbstractAegVenueImporter.fillGapsFromOverview].
 *
 * @see AegOverviewPageScraper for the listing parser (title, date, category, price).
 * @see AbstractAegVenueImporter for the HTTP fetch orchestrator.
 */
class AegDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page carries no event
     * heading — the marker that the request did not return a real event page.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive the
     *   [ScrapedEvent.sourceId].
     * @param eventSource the venue whose page this is, used for the `sourceId` prefix.
     */
    @Suppress("ReturnCount") // A guard clause for the missing heading is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String,
        eventSource: EventSource
    ): ScrapedEvent? {
        val heading = document.textAt("h1.summary")
        if (heading == null) {
            logger.warn { "$eventSource detail page at $sourceUrl has no heading, skipping" }
            return null
        }
        val slug = extractEventSlug(sourceUrl, "/events/detail/")

        return ScrapedEvent(
            // The listing owns the title: this heading carries an "in der <venue>" tail.
            title = heading,
            description = document.textAt(".event_body"),
            // The listing owns the date; the sentinel lets it fill this in at the merge.
            eventDate = UNRESOLVED_EVENT_DATE,
            doorsTime = parseDoorsTime(document.textAt(".doors")),
            sourceUrl = sourceUrl,
            sourceId = "${eventSource.sourceIdPrefix}$slug",
            ticketUrl = parseTicketUrl(document, sourceUrl)
        )
    }

    /**
     * Reads the external ticket-shop link (AXS for both venues) from the `#tickets` block.
     *
     * The music hall renders a **self-link before** the shop link with identical classes, so
     * taking the first `a.btn-tix` would store the event's own URL as its ticket link. Only a link
     * pointing off the venue's own host qualifies.
     */
    private fun parseTicketUrl(
        document: Document,
        sourceUrl: String
    ): String? {
        val venueHost = runCatching { URI(sourceUrl).host }.getOrNull()
        return document
            .select("#tickets a.btn-tix[href]")
            .map { it.attr("href") }
            .firstOrNull { href ->
                href.startsWith("http") && runCatching { URI(href).host }.getOrNull() != venueHost
            }
    }

    /**
     * Parses the doors time from the venue's `", Einlass 18:30 Uhr"` line, the only place a doors
     * time appears at all. Returns `null` when the page states none.
     */
    private fun parseDoorsTime(text: String?): LocalTime? = parseTime(DOORS_PATTERN.find(text.orEmpty())?.groupValues?.get(1))
}

/** Matches the venues' `"Einlass HH:mm Uhr"` doors line. */
private val DOORS_PATTERN = Regex("""Einlass\s+(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)
