package de.norm.events.scraper.colosseum

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WIX_REGISTRATION_OPEN_TICKETS
import de.norm.events.scraper.WIX_REGISTRATION_TICKETS
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.buildArtistList
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.colosseum.ColosseumOverviewPageScraper.Companion.CINEMA_AS_PLACE
import de.norm.events.scraper.colosseum.ColosseumOverviewPageScraper.Companion.VENUE_FORMATS
import de.norm.events.scraper.extractSupportFromSubtitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.mapWixEventStatus
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.parseWixTicketPrice
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.wixPriceRangeNote
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * Pure parser for Colosseum's Wix Events programme page (`/event`).
 *
 * Every field comes from the embedded `wix-warmup-data` JSON (see [WixEventsWarmupData]) — the
 * rendered cards are never read. As at MAXXIM, the payload carries prices and the sold-out flag,
 * so the single overview fetch is complete and no per-event page is fetched. The event `slug`
 * still yields the canonical [ScrapedEvent.sourceUrl] and the stable [ScrapedEvent.sourceId];
 * this site publishes its detail pages under `/details-registrierung/<slug>` rather than Wix's
 * default, and the payload's own `siteSettings.detailsPagePath` says `"details"`, not the live
 * path — so the path is a constant here.
 *
 * **`registration.ticketing` lies for externally ticketed events.** Three of the eighteen live
 * events sell through a promoter's shop (`registration.type == 3`); Wix still emits a
 * `ticketing` node for them and — with no Wix ticket definitions — reports `"soldOut": true`
 * while the page renders a working "Tickets kaufen" button. The block is read only when Wix
 * itself sells the tickets ([WIX_REGISTRATION_TICKETS]). The ticket URL is the external shop,
 * or the event's own page, where an open Wix sale has its checkout.
 *
 * With no support-act convention in the subtitles, [buildArtistList] extracts nothing: a
 * Colosseum title is as often an event name ("Investment", "Das Betreute Singen September") as
 * a performer's, so minting it as a headliner would create artists that are not people.
 *
 * Typing: [VENUE_FORMATS] first, then [inferUnmarkedTitleType] over title and subtitle with
 * [CINEMA_AS_PLACE] removed, else `OTHER` — the house publishes no category (`categories` is
 * empty on every event), and `CONCERT` would be wrong for a talks-and-readings room. A talk lands
 * on `OTHER` too: the model has no `TALK` type.
 *
 * @see COLOSSEUM_LIMITATIONS for what the house does not publish.
 * @see ColosseumWebsiteImporter for the HTTP fetch orchestrator.
 */
class ColosseumOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the programme page's embedded Wix warmup payload, one per listed event.
     *
     * @param baseUrl the URL the document was fetched from, for the `/details-registrierung/<slug>` URLs.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = WixEventsWarmupData.events(document, EventSource.COLOSSEUM) ?: return emptyList()
        logger.info { "Found ${events.size()} event(s) in Colosseum Wix warmup payload" }

        return events.mapSkippingFailures(logger, "Colosseum event") { node ->
            parseEvent(node, baseUrl)
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
        // No detail page is fetched, so an event without a resolvable startDate has no second chance
        // at a date — drop it rather than persist a sentinel.
        val schedule = parseWixSchedule(node.path("scheduling").path("config"))
        val eventDate = schedule.date
        if (eventDate == null) {
            logger.warn { "Colosseum event '$slug' has no parseable start date, skipping" }
            return null
        }

        // Wix's `description` is the one-line strapline under the title ("mit Thomas Schaaf &
        // Freunden", "CEO, Wirtschaftsmanager, Aufsichtsrat") — a subtitle, not a description.
        val subtitle = node.stringOrNull("description")
        val registration = node.path("registration")
        val soldByWix = registration.path("type").asInt(0) == WIX_REGISTRATION_TICKETS
        val ticketing = registration.path("ticketing").takeIf { soldByWix }
        val eventPage = resolveUrl(baseUrl, "$DETAILS_PATH$slug")
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType(title, subtitle),
            eventDate = eventDate,
            startTime = schedule.startTime,
            endDate = schedule.endDate,
            endTime = schedule.endTime,
            imageUrl = node.path("mainImage").stringOrNull("url"),
            sourceUrl = eventPage,
            sourceId = "${EventSource.COLOSSEUM.sourceIdPrefix}$slug",
            ticketUrl =
                registration.path("external").stringOrNull("registration")
                    ?: eventPage.takeIf { soldByWix && registration.path("status").asInt(0) == WIX_REGISTRATION_OPEN_TICKETS },
            pricePresale = ticketing?.let { parseWixTicketPrice(it.path("lowestTicketPrice")) },
            priceNote = ticketing?.let { wixPriceRangeNote(it) },
            soldOut = ticketing?.path("soldOut")?.asBoolean(false) == true,
            status = mapWixEventStatus(node.path("status")),
            artists = buildArtistList(title, extractSupportFromSubtitle(subtitle))
        )
    }

    private fun eventType(
        title: String,
        subtitle: String?
    ): String {
        val text = listOfNotNull(title, subtitle).joinToString(" ")
        return VENUE_FORMATS.entries.firstOrNull { (cue, _) -> cue.containsMatchIn(text) }?.value
            ?: inferUnmarkedTitleType(CINEMA_AS_PLACE.replace(text, ""))
    }

    private companion object {
        /** Path prefix of a Colosseum event's own page, e.g. `/details-registrierung/irvine-welsh-live`. */
        private const val DETAILS_PATH = "/details-registrierung/"

        /**
         * Formats this house names in its own words that the shared classifier misses: a film night as
         * "… - Film: <title>" or its "Kinoevents" series, a book launch as a "Buchpremiere" or a
         * "Roman" or "Buch" presented, a "Lesung", and podcasts recorded on stage before an audience —
         * a staged show. Checked before [inferUnmarkedTitleType], first match wins.
         *
         * The screening cues come first on purpose: a film night is regularly *presented by* a
         * podcast ("… Kinoevents 2026, presented by … Podcast & ByteFM"), and what the audience watches
         * decides the type, not who hosts it. The book cues come before the shared cues, which would
         * read the building's "Kino" first (#2562). `roman` and `buch` match as whole words, so
         * "Romantik" and "Tagebuch" stay untyped; `buchpremiere` and `lesung` match inside a compound
         * ("Kinderbuchpremiere").
         */
        private val VENUE_FORMATS: Map<Regex, String> =
            linkedMapOf(
                Regex("""film:|kinoevent""", RegexOption.IGNORE_CASE) to EventType.SCREENING.name,
                Regex("""buchpremiere|lesung|(?<!\p{L})(?:roman|buch)(?!\p{L})""", RegexOption.IGNORE_CASE) to EventType.READING.name,
                Regex("""podcast""", RegexOption.IGNORE_CASE) to EventType.SHOW.name
            )

        /**
         * The house itself, named as a place: "im historischen Berliner Kino". Every event here is in a
         * former cinema, so the word says where, not what; a film night carries a [VENUE_FORMATS] cue.
         */
        private val CINEMA_AS_PLACE = Regex("""\bim\s+(?:\p{L}+\s+){0,3}kino\b""", RegexOption.IGNORE_CASE)
    }
}
