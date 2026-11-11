package de.norm.events.scraper.maxxim

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.mapWixEventStatus
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.parseWixTicketPrice
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.wixPriceRangeNote
import de.norm.events.scraper.wixTicketing
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode

/**
 * Pure parser for MAXXIM's Wix Events programme page (`/partys`).
 *
 * Every field comes from the embedded `wix-warmup-data` JSON (see [WixEventsWarmupData]) — the
 * rendered cards are never read. Unlike Loge, whose payload omits the price, MAXXIM's
 * `registration.ticketing` block carries ticket price and sold-out flag, so the single overview
 * fetch is complete: no `/event-details/<slug>` page is fetched (its `<slug>` still serves the
 * canonical [ScrapedEvent.sourceUrl] and the stable [ScrapedEvent.sourceId]).
 *
 * The club names its DJs only in the description, each behind a `DJ` or `DJane` prefix; [djsIn] bills
 * those. A partner or host the prose names without the prefix (`DJ SIZMO & Laura Blue`, `Hosted by
 * Nico Muolo`) is billed nobody, because the prose does not say what that person does.
 *
 * @see MaxximWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.maxxim-berlin.de/partys">MAXXIM programme</a>
 */
class MaxximOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the programme page's embedded Wix warmup payload, one per listed night.
     *
     * @param baseUrl the URL the document was fetched from, for the `/event-details/<slug>` URLs.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = WixEventsWarmupData.events(document, EventSource.MAXXIM) ?: return emptyList()
        logger.info { "Found ${events.size()} event(s) in MAXXIM Wix warmup payload" }

        return events.mapSkippingFailures(logger, "MAXXIM event") { node ->
            parseEvent(node, baseUrl)
        }
    }

    private fun parseEvent(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val slug = node.stringOrNull("slug")
        if (slug == null) {
            logger.warn { "MAXXIM event has no slug, skipping" }
            return null
        }
        val cleanedTitle = node.stringOrNull("title")?.let { cleanEventTitle(it) }
        val title = cleanedTitle?.text
        if (title.isNullOrBlank()) {
            logger.warn { "MAXXIM event '$slug' has no title, skipping" }
            return null
        }
        // Unlike Loge there is no detail page to recover a to-be-decided date from, so an event
        // without a resolvable startDate is dropped rather than persisted with a sentinel.
        val schedule = parseWixSchedule(node.path("scheduling").path("config"))
        val eventDate = schedule.date
        if (eventDate == null) {
            logger.warn { "MAXXIM event '$slug' has no parseable start date, skipping" }
            return null
        }

        // Every night in the live programme is Wix-ticketed, but the guard costs nothing and keeps a
        // future externally ticketed night from being reported sold out — see [WIX_REGISTRATION_TICKETS].
        val registration = node.path("registration")
        val ticketing = registration.wixTicketing()
        val description = node.stringOrNull("description")
        return ScrapedEvent(
            title = title,
            description = description,
            // MAXXIM publishes no categories: a club whose every night is a DJ dance party.
            eventType = EventType.PARTY.name,
            // The club names no style but plays disco and party classics, so the venue is the default.
            eventDate = eventDate,
            startTime = schedule.startTime,
            endDate = schedule.endDate,
            endTime = schedule.endTime,
            imageUrl = node.path("mainImage").stringOrNull("url"),
            sourceUrl = resolveUrl(baseUrl, "/event-details/$slug"),
            sourceId = "${EventSource.MAXXIM.sourceIdPrefix}$slug",
            pricePresale = ticketing?.let { parseWixTicketPrice(it.path("lowestTicketPrice")) },
            priceNote = ticketing?.let { wixPriceRangeNote(it) },
            soldOut = ticketing?.path("soldOut")?.asBoolean(false) == true || cleanedTitle.soldOut,
            status = mapWixEventStatus(node.path("status")),
            artists = djsIn(description).map { ScrapedArtist(name = it, role = ArtistRole.DJ) }
        )
    }
}

/**
 * The DJs [description] names with a `DJ` or `DJane` prefix, prefix kept, as the venue writes them:
 * `DJ Tiefton, DJ DeeNoRee und DJ Punjesh` bills all three, `DJane Hanna Kirsanova & DJ SIZMO` both. A name
 * is up to three capitalised words, so it ends at a comma, a dash, `&`, a lowercase word (`plays`, `mit`,
 * `on the decks`), a conjunction in capitals (`DJ ONE F AND FRIENDS`) or the end of a sentence.
 */
internal fun djsIn(description: String?): List<String> =
    description
        ?.let { DJ_NAME.findAll(it) }
        ?.map { it.value.trimEnd('.') }
        ?.filterNot(::isNonArtistName)
        ?.distinctBy { it.lowercase() }
        ?.toList()
        .orEmpty()

private const val NAME_WORD = """(?!(?i:and|und|mit|with|feat)\b)[\p{Lu}\d][\p{L}\d'’.-]*"""
private val DJ_NAME = Regex("""\bDJ(?:ane)? +$NAME_WORD(?: +$NAME_WORD){0,2}""")
