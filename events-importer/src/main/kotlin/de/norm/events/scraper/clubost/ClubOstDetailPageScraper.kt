package de.norm.events.scraper.clubost

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for a Club OST event detail page (`/event/<id>/`).
 *
 * The page is a **stub by design**: the club publishes its programme on Resident Advisor and uses its
 * own site as a shopfront, so every detail page prints the same three placeholder sentences where a
 * description, a further-information note and a flyer would go. Of the four fields it states — date,
 * start time, end time, description — the first two only repeat the listing, the description is
 * always a placeholder, and **the model has no end-time field**, so the page adds no event data.
 *
 * It is still fetched, for one reason: the **title's real casing**. The listing template upper-cases
 * every title (`RAVE THE PLANET TRUCK`), which is presentation, not the name the venue typed; this
 * page prints it as entered (`Rave The Planet Truck`), and storing the shouted form would be storing
 * a CSS decision as data. The cost is one extra request per event against a programme of well under
 * a dozen, so a two-page fetch is cheap here in a way it would not be for a venue listing hundreds.
 *
 * The end time the page states — often the following morning, `11 p.m.` → `8 a.m.` — is
 * **deliberately dropped**: [ScrapedEvent][de.norm.events.scraper.ScrapedEvent] carries doors and
 * start only, and inventing a same-day end from it would be wrong for every night that runs past
 * midnight, which is most of them. That is a limitation of the model, not of this parser.
 *
 * There are no CMS classes to key on: the title is the content column's `h1`, the four fields are
 * `p` rows introduced by a `<strong>` label, and the ticket link is `a.button-link.ticket`.
 *
 * @see ClubOstOverviewPageScraper for discovery and the fallback data this merges over.
 */
class ClubOstDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the event on a detail page.
     *
     * Returns `null` when the page has no content container, no title or no parseable date —
     * the importer then keeps the overview card's data instead, which states the same date and
     * time and differs only in the title's casing.
     *
     * @param sourceUrl the URL the page was fetched from, used as
     *   [ScrapedEvent.sourceUrl][de.norm.events.scraper.ScrapedEvent.sourceUrl] and to derive
     *   the `sourceId`.
     */
    @Suppress("ReturnCount") // Guard clauses per missing required field read better than a nested let-chain
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        // The block carries no class of its own, so it is identified by the "back to homepage"
        // control unique to it. Scoping here keeps the bare `h1` and `p` selectors below from
        // matching the site header and footer, which use the same tags.
        val content = document.selectFirst(CONTENT_CONTAINER)
        if (content == null) {
            logger.warn { "Club OST detail page at $sourceUrl has no content container, skipping" }
            return null
        }

        val eventId = extractClubOstEventId(sourceUrl)
        if (eventId == null) {
            logger.warn { "Club OST detail URL $sourceUrl carries no event id, skipping" }
            return null
        }

        val title = content.textAt("h1")?.let(::cleanEventTitle)
        if (title.isNullOrBlank()) {
            logger.warn { "Club OST detail page at $sourceUrl has no title, skipping" }
            return null
        }

        val eventDate = parseClubOstDate(content.valueForLabel(DATE_LABEL))
        if (eventDate == null) {
            logger.warn { "Club OST detail page at $sourceUrl has no parseable date, skipping" }
            return null
        }

        return ScrapedEvent(
            title = title,
            eventDate = eventDate,
            startTime = parseClubOstTime(content.valueForLabel(START_TIME_LABEL)),
            eventType = EventType.PARTY.name,
            description = withoutPlaceholder(content.valueForLabel(DESCRIPTION_LABEL)),
            imageUrl = content.imgSrcAt("img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.CLUB_OST.sourceIdPrefix}$eventId",
            ticketUrl = content.hrefAt(TICKET_LINK)
        )
    }

    /**
     * Reads the value of the `<p><strong>Label:</strong> value</p>` row carrying [label].
     *
     * Matching on the label text rather than on the paragraph's position keeps the parser
     * working when the venue adds or reorders a row, and is the only semantic handle the page
     * offers — the rows share no classes. Returns `null` when no row carries the label or the
     * remainder is blank.
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

        /** Label introducing the description row. */
        private const val DESCRIPTION_LABEL = "Description:"
    }
}
