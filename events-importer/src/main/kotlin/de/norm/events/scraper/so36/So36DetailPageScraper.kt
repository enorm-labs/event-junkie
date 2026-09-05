package de.norm.events.scraper.so36

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ROLE_LABEL_PREFIX
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.refineConcertVenueType
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Pure HTML parser for SO36 event detail (`/produkte/…`) pages.
 *
 * The detail page is the **primary data source** for each event. Most fields are
 * read straight from the server-rendered HTML and schema.org microdata:
 * - title (`h1 [itemprop=name]`), category (`.supertitle`), subtitle (`.subtitle`)
 * - doors / start times (the "Einlass … Beginn …" clock line)
 * - description (`.product_description`), poster image (`og:image`)
 * - ticket price (`[itemprop=price]` content) and the external ticket-shop link
 *
 * Only two fields are taken from the page's schema.org `Event` JSON-LD block,
 * because they have no reliable HTML rendering: the ISO `startDate` (a four-digit,
 * time-zoned date) and the `eventStatus` (scheduled / cancelled / postponed).
 * The JSON-LD *offer* `availability` is intentionally **ignored** for sold-out
 * detection: SO36 sells most events through external shops, which report the
 * on-platform availability as `SoldOut` even when tickets are freely available
 * elsewhere — so it is not a trustworthy signal.
 *
 * @see So36OverviewPageScraper for overview parsing (discovery, date/title fallback).
 * @see So36WebsiteImporter for the HTTP fetch orchestrator.
 */
class So36DetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent].
     *
     * Returns `null` if the event title is missing (an unexpected page structure),
     * so the importer can fall back to the overview data.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to
     *   derive the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clause for the required title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val title = document.textAt("h1 [itemprop=name]")
        if (title.isNullOrBlank()) {
            logger.warn { "Detail page at $sourceUrl has no event title, skipping" }
            return null
        }

        val eventType = refineConcertVenueType(mapEventType(document.textAt("small.supertitle:not(.ticketsfor)")), title)
        val subtitle = document.textAt("small.subtitle")
        val jsonLd = document.parseEventJsonLd()
        val (doorsTime, startTime) = parseTimes(document)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = parseDescription(document),
            eventType = eventType,
            // The detail JSON-LD carries the authoritative date; sentinel when absent,
            // so the overview's date is used via So36WebsiteImporter.fillGapsFromOverview.
            eventDate = jsonLd.startDate?.let { parseIsoDate(it) } ?: UNRESOLVED_EVENT_DATE,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = parseImageUrl(document),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SO36.sourceIdPrefix}${extractProductId(sourceUrl)}",
            ticketUrl = document.hrefAt(".variants-listing a.btn-buyme"),
            pricePresale = parsePresalePrice(document),
            status = mapSchemaStatus(jsonLd.eventStatus),
            artists = parseArtists(title, subtitle, eventType)
        )
    }

    /**
     * Parses the "Einlass: HH:mm … Beginn: HH:mm" clock line into a
     * (doors, start) time pair. Scoped to the block carrying the clock icon so a
     * stray time elsewhere on the page cannot be mistaken for it.
     */
    private fun parseTimes(document: Document): Pair<LocalTime?, LocalTime?> {
        val clockText =
            document
                .select(".inside")
                .firstOrNull { it.selectFirst("i.fa-clock-o") != null }
                ?.text()
                .orEmpty()
        val doorsTime = parseTime(EINLASS_PATTERN.find(clockText)?.groupValues?.get(1))
        val startTime = parseTime(BEGINN_PATTERN.find(clockText)?.groupValues?.get(1))
        return doorsTime to startTime
    }

    /** Extracts the poster image from the Open Graph `og:image` meta tag. */
    private fun parseImageUrl(document: Document): String? =
        document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.startsWith("http") }

    /** Joins the paragraphs of the `.product_description` block into the event description. */
    private fun parseDescription(document: Document): String? =
        document
            .select(".product_description p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /**
     * Reads the lowest ticket price from the schema.org offer microdata
     * (`[itemprop=price]` `content` attribute — a clean machine-readable value).
     * SO36's online price is a presale (Vorverkauf) price; the box-office price is
     * not exposed structurally. Events without online sales carry no offer and
     * yield `null`.
     */
    private fun parsePresalePrice(document: Document): BigDecimal? =
        document
            .select("[itemprop=price][content]")
            .mapNotNull { it.attr("content").toBigDecimalOrNull() }
            .minOrNull()

    /**
     * Builds the artist list for concert events: the title is the headliner (unless
     * a placeholder like "TBA"), followed by support acts. Support acts come from a
     * subtitle that starts with "+", SO36's convention for a support line
     * (e.g. "+ GUM + CLAVV"); a subtitle without a leading "+" is a descriptive
     * tagline (e.g. "Die Indie-Pop Party"), not a lineup, and yields no acts.
     * Non-concert events (parties, shows) carry no artist roster.
     *
     * The venue names a night and its acts as `"<night> mit <acts>"` ("SADTEMBER mit TAHA,
     * JOHNBOY M.IKARUS"), so the shared billing frame is switched on: the acts after the marker
     * are the headliners and the night's name is never one (#1132).
     */
    private fun parseArtists(
        title: String,
        subtitle: String?,
        eventType: String?
    ): List<ScrapedArtist> {
        if (eventType != EventType.CONCERT.name) return emptyList()

        val supportActs =
            parseSupportActs(subtitle).map { ScrapedArtist(name = it, role = "SUPPORT") }
        return headlinersFromTitle(title, unpackWithFrame = true) + supportActs
    }

    /**
     * Splits a leading-"+" support subtitle into individual act names.
     *
     * SO36 support lines vary: a bare list ("+ GUM + CLAVV") or a labelled one
     * ("+ Special Guest: FUCK", "+ Support: cosmic joke & bad beat"). Splitting is
     * delegated to [splitSupportActs], which cuts on commas, `+` and `/` and handles
     * `&` / `and` / `und` per boundary — so "Earth Tongue und Scott Hepple & The
     * Sun Band" yields "Earth Tongue" and "Scott Hepple & The Sun Band" rather than
     * mangling either name. Each act's leading role label ("Support:", "Special
     * Guest(s):", "div. Supports", …) is stripped, and any chunk that is not a real
     * act — a bare label, a placeholder like "TBA", or an event-segment label like
     * "ACID AFTERSHOW" ([isNonArtistName]) — is dropped so it never becomes a bogus
     * artist entry. A subtitle without a leading "+" is a tagline, not a lineup,
     * and yields nothing.
     */
    private fun parseSupportActs(subtitle: String?): List<String> {
        if (subtitle == null || !subtitle.trimStart().startsWith("+")) return emptyList()
        return splitSupportActs(subtitle.trimStart().removePrefix("+"))
            .map { it.replaceFirst(ROLE_LABEL_PREFIX, "").trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
    }

    /**
     * Maps a schema.org `eventStatus` URL to an [EventStatus] name.
     *
     * `EventRescheduled` (the event's date/time moved) maps to `POSTPONED` — the
     * closest domain status — rather than `RELOCATED`, which is reserved for venue
     * changes. Missing or unknown values default to `SCHEDULED`.
     */
    private fun mapSchemaStatus(eventStatus: String?): String {
        val status = eventStatus.orEmpty()
        return when {
            status.contains("Cancelled") -> EventStatus.CANCELLED.name
            status.contains("Postponed") || status.contains("Rescheduled") -> EventStatus.POSTPONED.name
            else -> EventStatus.SCHEDULED.name
        }
    }

    /** Extracts the numeric product id from a `/produkte/<id>-…` detail URL. */
    private fun extractProductId(url: String): String =
        PRODUCT_ID_PATTERN
            .find(url)
            ?.groupValues
            ?.get(1)
            .orEmpty()

    /**
     * The two scalar fields read from the page's schema.org `Event` JSON-LD block.
     * Extracted with targeted regexes (matching the [de.norm.events.scraper.privatclub]
     * convention) rather than a JSON parser, since only two flat string fields are needed.
     */
    private data class EventJsonLd(
        val startDate: String?,
        val eventStatus: String?
    )

    /**
     * Locates the schema.org `Event` JSON-LD script and extracts the [EventJsonLd]
     * fields. Returns an all-`null` instance when no such block is present.
     */
    private fun Document.parseEventJsonLd(): EventJsonLd {
        val json =
            select("script[type=application/ld+json]")
                .map { it.data() }
                .firstOrNull { it.contains("\"@type\"") && it.contains("Event") }
                ?: return EventJsonLd(startDate = null, eventStatus = null)
        return EventJsonLd(
            startDate = extractJsonLdField(json, "startDate"),
            eventStatus = extractJsonLdField(json, "eventStatus")
        )
    }

    /** Extracts a flat `"field": "value"` string from JSON text, or `null` if absent. */
    private fun extractJsonLdField(
        json: String,
        field: String
    ): String? = Regex(""""$field"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

    private companion object {
        /** Extracts "Einlass: HH:mm" from the clock line. */
        private val EINLASS_PATTERN = Regex("""Einlass:\s*(\d{1,2}:\d{2})""")

        /** Extracts "Beginn: HH:mm" from the clock line. */
        private val BEGINN_PATTERN = Regex("""Beginn:\s*(\d{1,2}:\d{2})""")

        /** Captures the numeric product id from a `/produkte/<id>-…` path. */
        private val PRODUCT_ID_PATTERN = Regex("""/produkte/(\d+)""")
    }
}
