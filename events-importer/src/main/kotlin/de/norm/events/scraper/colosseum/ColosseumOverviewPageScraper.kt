package de.norm.events.scraper.colosseum

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WIX_REGISTRATION_TICKETS
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.buildArtistList
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.colosseum.ColosseumOverviewPageScraper.Companion.VENUE_FORMAT_KEYWORDS
import de.norm.events.scraper.colosseum.ColosseumOverviewPageScraper.Companion.resolveEventType
import de.norm.events.scraper.extractSupportFromSubtitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapWixEventStatus
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * Pure parser for Colosseum's Wix Events programme page (`/event`).
 *
 * Every field comes from the embedded `wix-warmup-data` JSON (see [WixEventsWarmupData]) — the
 * rendered cards are never read. As at MAXXIM, the payload already carries prices and the sold-out
 * flag, so the single overview fetch is complete and no per-event page is fetched. The event
 * `slug` still yields the canonical [ScrapedEvent.sourceUrl] and the stable [ScrapedEvent.sourceId];
 * this site publishes its detail pages under `/details-registrierung/<slug>` rather than Wix's
 * default `/event-details/<slug>` (the payload's own `siteSettings.detailsPagePath` says
 * `"details"`, which is not the live path, so the path is a constant here).
 *
 * Traps this parser handles:
 * - **`registration.ticketing` lies for externally ticketed events.** Three of the eighteen live
 *   events sell through a promoter's shop (`registration.type == 3`); Wix still emits a `ticketing`
 *   node for them, and — because the event has no Wix ticket definitions — it reports
 *   `"soldOut": true` while the page renders a working "Tickets kaufen" button. The whole ticketing
 *   block is therefore read only when Wix itself sells the tickets ([WIX_REGISTRATION_TICKETS]);
 *   for the external ones the shop URL becomes the [ScrapedEvent.ticketUrl] instead.
 * - **The house states no category.** `categories` is empty on every event, so the type is inferred
 *   from the title and subtitle ([resolveEventType]).
 *
 * What the source does not carry, and is therefore left null rather than guessed:
 * - **No doors time.** Wix publishes one `startDate` per event and nothing else; the detail page's
 *   "Einlass: 19 Uhr" line is boilerplate (see [ColosseumWebsiteImporter]).
 * - **No genre and no lineup.** With no support-act convention in the subtitles,
 *   [buildArtistList] extracts nothing — a Colosseum title is as often an event name
 *   ("Investment", "Das Betreute Singen September") as a performer's, so minting it as a headliner
 *   would create artists that are not people.
 *
 * @see ColosseumWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.colosseumberlin.com/event">Colosseum programme</a>
 */
class ColosseumOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the programme page's embedded Wix warmup payload.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the per-event
     *   `/details-registrierung/<slug>` URLs.
     * @return a list of [ScrapedEvent] instances, one per listed event.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = WixEventsWarmupData.events(document, EventSource.COLOSSEUM) ?: return emptyList()
        logger.info { "Found ${events.size()} event(s) in Colosseum Wix warmup payload" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import
        return events.mapNotNull { node ->
            try {
                parseEvent(node, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Colosseum event, skipping" }
                null
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required slug, title and date are clearer than nesting
    private fun parseEvent(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val slug = node.stringOrNull("slug")
        if (slug == null) {
            logger.warn { "Colosseum event has no slug, skipping" }
            return null
        }
        val title = node.stringOrNull("title")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Colosseum event '$slug' has no title, skipping" }
            return null
        }
        // No detail page is fetched, so an event without a resolvable startDate has no second
        // chance at a date — drop it rather than persist a sentinel.
        val (eventDate, startTime) = parseWixSchedule(node.path("scheduling").path("config"))
        if (eventDate == null) {
            logger.warn { "Colosseum event '$slug' has no parseable start date, skipping" }
            return null
        }

        // Wix's `description` is the one-line strapline under the title ("mit Thomas Schaaf &
        // Freunden", "CEO, Wirtschaftsmanager, Aufsichtsrat") — a subtitle, not a description.
        val subtitle = node.stringOrNull("description")
        val registration = node.path("registration")
        val ticketing = registration.path("ticketing").takeIf { registration.path("type").asInt(0) == WIX_REGISTRATION_TICKETS }
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = resolveEventType(title, subtitle),
            eventDate = eventDate,
            startTime = startTime,
            imageUrl = node.path("mainImage").stringOrNull("url"),
            sourceUrl = resolveUrl(baseUrl, "$DETAILS_PATH$slug"),
            sourceId = "${EventSource.COLOSSEUM.sourceIdPrefix}$slug",
            ticketUrl = registration.path("external").stringOrNull("registration"),
            pricePresale = ticketing?.let { parseTicketPrice(it.path("lowestTicketPrice")) },
            priceNote = ticketing?.let { parsePriceRangeNote(it) },
            soldOut = ticketing?.path("soldOut")?.asBoolean(false) == true,
            status = mapWixEventStatus(node.path("status")),
            artists = buildArtistList(title, extractSupportFromSubtitle(subtitle))
        )
    }

    /**
     * Reads a Wix ticket price node (`{"amount": "19.30", "currency": "EUR"}`). Returns `null` when
     * the amount is absent or not a number — an event with no ticket definitions carries none.
     *
     * The figure is Wix's checkout total, i.e. the face value plus the service fee Wix adds on top
     * (`wixFeeConfig.type: 2`), which is what a buyer actually pays: a ticket the venue names
     * "Standard (25€ + 2,5€ Gebühr)" is listed here at €28.19. The face value alone is only on the
     * detail page, which is deliberately not fetched (see [ColosseumWebsiteImporter]).
     */
    private fun parseTicketPrice(price: JsonNode): BigDecimal? = price.stringOrNull("amount")?.toBigDecimalOrNull()

    /**
     * Builds a price note only when an event has several ticket tiers, i.e. when the lowest and
     * highest formatted prices differ (`"€12.00 – €30.00"`). A single-tier event — the normal case
     * here — needs no note: [ScrapedEvent.pricePresale] already says everything.
     */
    private fun parsePriceRangeNote(ticketing: JsonNode): String? {
        val lowest = ticketing.stringOrNull("lowestTicketPriceFormatted")
        val highest = ticketing.stringOrNull("highestTicketPriceFormatted")
        return if (lowest != null && highest != null && lowest != highest) "$lowest – $highest" else null
    }

    private companion object {
        /** Path prefix of a Colosseum event's own page, e.g. `/details-registrierung/irvine-welsh-live`. */
        private const val DETAILS_PATH = "/details-registrierung/"

        /**
         * The event type, from the title and subtitle only — the house states no category
         * (`categories` is empty on every event).
         *
         * A format this house names in its own words ([VENUE_FORMAT_KEYWORDS]) wins; otherwise the
         * shared [inferUnmarkedTitleType] applies its unambiguous keyword cues and falls back to
         * `OTHER`. It deliberately does not default to `CONCERT`: this is a talks-and-readings
         * house whose occasional gig is the exception, and `OTHER` is also where a talk lands —
         * the model has no `TALK` type.
         */
        private fun resolveEventType(
            title: String,
            subtitle: String?
        ): String {
            val haystack = listOfNotNull(title, subtitle).joinToString(" ").lowercase()
            return VENUE_FORMAT_KEYWORDS.entries.firstOrNull { (keyword, _) -> keyword in haystack }?.value
                ?: inferUnmarkedTitleType(haystack)
        }

        /**
         * Formats this house names in its own words, which the shared classifier does not cover: it
         * announces a film night as "… - Film: <title>" or as part of its "Kinoevents" series, a
         * book launch as a "Buchpremiere", and records podcasts on stage in front of an audience —
         * a staged show. Checked before [inferUnmarkedTitleType], lowercase, first match wins.
         *
         * The two screening cues come first on purpose: a film night is regularly *presented by* a
         * podcast ("… Kinoevents 2026, presented by … Podcast & ByteFM"), and what the audience
         * watches decides the type, not who hosts it.
         */
        private val VENUE_FORMAT_KEYWORDS: Map<String, String> =
            linkedMapOf(
                "film:" to EventType.SCREENING.name,
                "kinoevent" to EventType.SCREENING.name,
                "buchpremiere" to EventType.READING.name,
                "podcast" to EventType.SHOW.name
            )
    }
}
