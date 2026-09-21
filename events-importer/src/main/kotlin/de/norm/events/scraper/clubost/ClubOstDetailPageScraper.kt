package de.norm.events.scraper.clubost

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.endOn
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for a Club OST detail page (`/event/<id>/`), a stub by design: the club
 * publishes on Resident Advisor and every page prints the same three placeholder sentences for
 * description, further information and flyer. Of its four fields the date and start repeat the
 * listing and the description is a placeholder; the end time is the one fact the listing lacks.
 *
 * Fetched for the title's real casing: the listing template upper-cases every title (`RAVE THE
 * PLANET TRUCK`), and storing that stores a CSS decision as data; this page prints `Rave The
 * Planet Truck`. One extra request per event against a programme of under a dozen. The end is
 * often the following morning, `11 p.m.` to `8 a.m.`, with no end date; [endOn] resolves an end
 * at or before the start as the next day (ADR-029). No CMS classes: the title is the content
 * column's `h1`, the fields are `p` rows introduced by a `<strong>` label, the ticket link is
 * `a.button-link.ticket`.
 *
 * @see ClubOstOverviewPageScraper for discovery and the fallback data this merges over.
 */
class ClubOstDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the event on a detail page, or `null` without a content container, title or parseable
     * date; the importer then keeps the card's data, which differs only in casing.
     *
     * @param sourceUrl the URL the page was fetched from,
     * [ScrapedEvent.sourceUrl][de.norm.events.scraper.ScrapedEvent.sourceUrl] and the `sourceId`
     * source.
     */
    @Suppress("ReturnCount") // Guard clauses per missing required field read better than a nested let-chain
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        // The block has no class, so it is identified by its unique "back to homepage" control; scoping
        // keeps the bare `h1` and `p` selectors off the header and footer.
        val content = document.selectFirst(CONTENT_CONTAINER)
        if (content == null) {
            logger.warn { "Club OST detail page has no content container, skipping" }
            return null
        }

        val eventId = extractClubOstEventId(sourceUrl)
        if (eventId == null) {
            logger.warn { "Club OST detail URL carries no event id, skipping" }
            return null
        }

        val title = content.textAt("h1")?.let(::cleanEventTitle)
        if (title.isNullOrBlank()) {
            logger.warn { "Club OST detail page has no title, skipping" }
            return null
        }

        val eventDate = parseClubOstDate(content.valueForLabel(DATE_LABEL))
        if (eventDate == null) {
            logger.warn { "Club OST detail page has no parseable date, skipping" }
            return null
        }

        val startTime = parseClubOstTime(content.valueForLabel(START_TIME_LABEL))
        val endTime = parseClubOstTime(content.valueForLabel(END_TIME_LABEL))
        return ScrapedEvent(
            title = title,
            eventDate = eventDate,
            startTime = startTime,
            endDate = endTime?.let { endOn(eventDate, startTime, it) },
            endTime = endTime,
            eventType = EventType.PARTY.name,
            description = withoutPlaceholder(content.valueForLabel(DESCRIPTION_LABEL)),
            imageUrl = content.imgSrcAt("img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.CLUB_OST.sourceIdPrefix}$eventId",
            ticketUrl = content.hrefAt(TICKET_LINK)
        )
    }

    /**
     * The value of the `<p><strong>Label:</strong> value</p>` row carrying [label], matched on the
     * label text since the rows share no classes; `null` when absent or blank.
     */
    private fun Element.valueForLabel(label: String): String? {
        val row =
            select("p:has(strong)").firstOrNull { paragraph ->
                paragraph.textAt("strong").equals(label, ignoreCase = true)
            }
        return row
            ?.text()
            ?.trim()
            ?.removePrefix(label)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** The event block, identified by the "back to homepage" control unique to it. */
        private const val CONTENT_CONTAINER = "div.container:has(a.back-button)"

        /** The Resident Advisor ticket link; `.ticket` separates it from the back-to-homepage button. */
        private const val TICKET_LINK = "a.button-link.ticket"

        /** Label introducing the event date row. */
        private const val DATE_LABEL = "Date:"

        /** Label introducing the start time row. */
        private const val START_TIME_LABEL = "Start time:"

        /** Label introducing the end time row, a clock time with no date of its own. */
        private const val END_TIME_LABEL = "End time:"

        /** Label introducing the description row. */
        private const val DESCRIPTION_LABEL = "Description:"
    }
}
