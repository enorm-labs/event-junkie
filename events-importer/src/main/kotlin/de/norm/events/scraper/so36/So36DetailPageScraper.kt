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
 * Pure HTML parser for SO36 event detail (`/produkte/…`) pages — the **primary data source**.
 *
 * Most fields come straight from the server-rendered HTML and schema.org microdata:
 * - title (`h1 [itemprop=name]`), category (`.supertitle`), subtitle (`.subtitle`)
 * - doors / start times (the "Einlass … Beginn …" clock line)
 * - description (`.product_description`), poster image (`og:image`)
 * - ticket price (`[itemprop=price]` content) and the external ticket-shop link
 * - free admission (the ticket tab's `Eintritt frei / Admission free!` notice)
 * - promoter (the `Anbieter/Veranstalter` field, `.product_merchant`)
 *
 * Only two fields come from the schema.org `Event` JSON-LD block, having no reliable HTML
 * rendering: the ISO `startDate` (four-digit, time-zoned) and the `eventStatus` (scheduled /
 * cancelled / postponed). The JSON-LD *offer* `availability` is deliberately **ignored** for
 * sold-out detection: SO36 sells most events through external shops, which report on-platform
 * availability as `SoldOut` even when tickets are freely available elsewhere.
 *
 * @see So36OverviewPageScraper for overview parsing (discovery, date/title fallback).
 * @see So36WebsiteImporter for the HTTP fetch orchestrator.
 */
class So36DetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without an event title, so the
     * importer can fall back to the overview data.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clause for the required title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val title = document.textAt("h1 [itemprop=name]")
        if (title.isNullOrBlank()) {
            logger.warn { "Detail page has no event title, skipping" }
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
            // The detail JSON-LD carries the authoritative date; sentinel when absent, so the overview's
            // date is used via So36WebsiteImporter.fillGapsFromOverview.
            eventDate = jsonLd.startDate?.let { parseIsoDate(it) } ?: UNRESOLVED_EVENT_DATE,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = parseImageUrl(document),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SO36.sourceIdPrefix}${extractProductId(sourceUrl)}",
            ticketUrl = document.hrefAt(".variants-listing a.btn-buyme"),
            pricePresale = parsePresalePrice(document),
            free = document.isFreeAdmission(),
            status = mapSchemaStatus(jsonLd.eventStatus),
            artists = parseArtists(title, subtitle, eventType),
            promoters = listOfNotNull(document.parsePromoter())
        )
    }

    /**
     * The "Einlass: HH:mm … Beginn: HH:mm" clock line as a (doors, start) pair. Scoped to the block
     * carrying the clock icon so a stray time elsewhere cannot be mistaken for it.
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

    /** The poster image from the Open Graph `og:image` meta tag. */
    private fun parseImageUrl(document: Document): String? =
        document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.startsWith("http") }

    /** The paragraphs of `.product_description` joined into the event description. */
    private fun parseDescription(document: Document): String? =
        document
            .select(".product_description p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /**
     * The lowest ticket price from the schema.org offer microdata (`[itemprop=price]` `content`, a
     * clean machine-readable value). The online price is a presale (Vorverkauf) price; the
     * box-office price is not exposed structurally. Events without online sales yield `null`.
     */
    private fun parsePresalePrice(document: Document): BigDecimal? =
        document
            .select("[itemprop=price][content]")
            .mapNotNull { it.attr("content").toBigDecimalOrNull() }
            .minOrNull()

    /**
     * The artist list for concerts: the title is the headliner (unless a placeholder like "TBA"),
     * then support acts from the subtitle ([parseSupportActs]). Non-concert events (parties, shows)
     * carry no roster.
     *
     * The venue names a night and its acts as `"<night> mit <acts>"` ("SADTEMBER mit TAHA, JOHNBOY
     * M.IKARUS"), so the shared billing frame is switched on: the acts after the marker are the
     * headliners and the night's name is never one (#1132).
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
     * Splits a support subtitle into act names. SO36 writes the line in two shapes: opening with a
     * joiner, "+" or "&" ("+ GUM + CLAVV", "+ Support: cosmic joke & bad beat", "& CRAWLSPACE &
     * PINTGLASS & GHETTO JUSTICE"), or with a support label ("Support: DEMOB HAPPY", "Special Guest:
     * The Flatliners"). Any other subtitle is a tagline ("Die Indie-Pop Party") and yields nothing —
     * `feat.` included, because "feat. Birte Volta mit Special-Guests" bills the headliner's guest,
     * not a support act (#1903).
     *
     * An "&" line is support, not a co-bill: SO36 puts co-headliners in the title ("PÖBEL &
     * GESOCKS"), and the NASTY page names only NASTY on its ticket and the "&" acts as "mit dabei"
     * (#1928).
     *
     * [splitSupportActs] cuts on commas, `+` and `/` and handles `&` / `and` /
     * `und` per boundary — "Earth Tongue und Scott Hepple & The Sun Band" yields "Earth Tongue"
     * and "Scott Hepple & The Sun Band" without mangling either. Each act's leading role label
     * ("Support:", "Special Guest(s):", "div. Supports", …) is stripped, and any chunk that is not
     * an act — a bare label, "TBA", an event-segment label like "ACID AFTERSHOW"
     * ([isNonArtistName]) — is dropped.
     */
    private fun parseSupportActs(subtitle: String?): List<String> {
        val line = subtitle?.trimStart().orEmpty()
        val joined = line.firstOrNull() in SUPPORT_JOINERS
        if (!joined && !SUPPORT_LABEL_OPENER.containsMatchIn(line)) return emptyList()
        return splitSupportActs(if (joined) line.drop(1) else line)
            .map { it.replaceFirst(ROLE_LABEL_PREFIX, "").trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
    }

    /**
     * Maps a schema.org `eventStatus` URL to an [EventStatus] name. `EventRescheduled` (date/time
     * moved) maps to `POSTPONED` — the closest — rather than `RELOCATED`, reserved for venue
     * changes. Missing or unknown defaults to `SCHEDULED`.
     */
    private fun mapSchemaStatus(eventStatus: String?): String {
        val status = eventStatus.orEmpty()
        return when {
            status.contains("Cancelled") -> EventStatus.CANCELLED.name
            status.contains("Postponed") || status.contains("Rescheduled") -> EventStatus.POSTPONED.name
            else -> EventStatus.SCHEDULED.name
        }
    }

    /** The numeric product id from a `/produkte/<id>-…` detail URL. */
    private fun extractProductId(url: String): String =
        PRODUCT_ID_PATTERN
            .find(url)
            ?.groupValues
            ?.get(1)
            .orEmpty()

    /**
     * The two scalar fields read from the schema.org `Event` JSON-LD block, extracted with
     * targeted regexes (the [de.norm.events.scraper.privatclub] convention) rather than a JSON
     * parser, since only two flat string fields are needed.
     */
    private data class EventJsonLd(
        val startDate: String?,
        val eventStatus: String?
    )

    /**
     * Locates the schema.org `Event` JSON-LD script and extracts the [EventJsonLd] fields; an
     * all-`null` instance when no block is present.
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

    /** A flat `"field": "value"` string from JSON text, or `null`. */
    private fun extractJsonLdField(
        json: String,
        field: String
    ): String? = Regex(""""$field"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

    private companion object {
        /** "Einlass: HH:mm" from the clock line. */
        private val EINLASS_PATTERN = Regex("""Einlass:\s*(\d{1,2}:\d{2})""")

        /** "Beginn: HH:mm" from the clock line. */
        private val BEGINN_PATTERN = Regex("""Beginn:\s*(\d{1,2}:\d{2})""")

        /** The characters that join a subtitle's acts to the headliner when they open it. */
        private val SUPPORT_JOINERS = setOf('+', '&')

        /** The support labels of [ROLE_LABEL_PREFIX] opening a subtitle, colon required. */
        private val SUPPORT_LABEL_OPENER =
            Regex("""^(?:div\.?\s*supports?|special\s+guests?|supports?|openers?)\s*:""", RegexOption.IGNORE_CASE)

        /** The numeric product id from a `/produkte/<id>-…` path. */
        private val PRODUCT_ID_PATTERN = Regex("""/produkte/(\d+)""")
    }
}

/**
 * Whether the ticket tab shows the free-admission notice where a price category would be. A free
 * night has no `€` figure at all, so the price microdata cannot say it.
 */
private fun Document.isFreeAdmission(): Boolean =
    select(".tabs .panel-body .alert")
        .any { FREE_ADMISSION_PATTERN.containsMatchIn(it.text()) }

/**
 * The `Anbieter/Veranstalter` name. The house's own nights credit `SO36`, the venue itself, so
 * that credit is dropped.
 */
private fun Document.parsePromoter(): String? =
    textAt(".product_merchant b")
        ?.takeUnless { it.equals(VENUE_NAME, ignoreCase = true) }

/** The ticket tab's notice for a free night: `Eintritt frei / Admission free!`. */
private val FREE_ADMISSION_PATTERN = Regex("""eintritt\s+frei|admission\s+free""", RegexOption.IGNORE_CASE)

/** The promoter credit on the house's own nights. */
private const val VENUE_NAME = "SO36"
