package de.norm.events.scraper.privatclub

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseIsoTime
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.privatclub.PrivatclubOverviewPageScraper.Companion.GERMAN_DATE_FORMATTER
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure HTML parser for Privatclub Berlin's WordPress-based event overview page.
 *
 * Privatclub renders all upcoming events on a single page (`/`) with full
 * details expanded inline (descriptions, prices, ticket links, promoters).
 * Each event also links to a dedicated detail page (e.g. `/event/sean-rowe-2/`),
 * but all data is already available on the overview page — no detail page
 * fetching is needed.
 *
 * Each event is accompanied by a `<script type="application/ld+json">` block
 * containing schema.org `MusicEvent` structured data. The JSON-LD is used as
 * the **primary source** for structured fields (`startDate`, `doorTime`,
 * `image`, `url`, ticket `offers`) because it is more reliable than CSS
 * selectors. HTML elements serve as **fallback** and provide fields not
 * present in JSON-LD (genre, subtitle, description, prices, status, promoter).
 *
 * @see PrivatclubWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://privatclub-berlin.de/">Privatclub Berlin</a>
 */
@Suppress("TooManyFunctions")
class PrivatclubOverviewPageScraper(
    /** Clock used for date calculations. Defaults to the system clock; override in tests for deterministic year-rollover logic. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

    /**
     * Parses all events from the Privatclub overview page document.
     *
     * Each event lives in a `.event_wrapper.skewed` element, followed by a
     * JSON-LD script tag with structured event data.
     *
     * @param baseUrl the URL the document was fetched from, used for resolving relative links.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val eventWrappers = document.select(".event_wrapper.skewed")
        logger.info { "Found ${eventWrappers.size} event wrapper(s) on page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the entire import
        return eventWrappers.mapNotNull { wrapper ->
            try {
                parseEventWrapper(wrapper, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse event wrapper, skipping" }
                null
            }
        }
    }

    /**
     * Parses a single `.event_wrapper` element into a [ScrapedEvent].
     *
     * Uses a two-layer extraction strategy:
     * 1. **JSON-LD** (primary) — structured data for date, doors time, image, URL, ticket URL
     * 2. **HTML** (fallback + enrichment) — title, subtitle, genre, description, prices, status, promoter, artists
     */
    @Suppress("CyclomaticComplexity", "CyclomaticComplexMethod", "ReturnCount", "LongMethod") // Cohesive single-event parsing with many optional fields
    private fun parseEventWrapper(
        wrapper: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val header = wrapper.selectFirst("a.event_header") ?: return null
        val detail = wrapper.selectFirst(".event_detail")

        // --- JSON-LD: primary source for structured fields ---
        val jsonLd = parseJsonLd(wrapper)

        // Title is the core required field (not in JSON-LD in a reliably clean form)
        val title = header.textAt("span.titel")
        if (title.isNullOrBlank()) {
            logger.warn { "Event wrapper has no title, skipping" }
            return null
        }

        // Event URL: prefer JSON-LD `url`, fall back to HTML href
        val href = header.attr("href")
        val eventUrl =
            jsonLd?.url
                ?: href.takeIf { it.isNotBlank() }?.let { resolveUrl(baseUrl, it) }
        if (eventUrl.isNullOrBlank()) {
            logger.warn { "Event '$title' has no URL, skipping" }
            return null
        }
        val slug = extractSlug(eventUrl)

        // Event date: prefer JSON-LD `startDate` (includes year), fall back to HTML
        val eventDate = jsonLd?.eventDate ?: parseDateFromHtml(header)
        if (eventDate == null) {
            logger.warn { "Could not parse event date for '$title', skipping" }
            return null
        }

        // Times: prefer JSON-LD, fall back to HTML
        val (htmlDoorsTime, htmlStartTime) = parseTimes(detail, header)
        val doorsTime = jsonLd?.doorsTime ?: htmlDoorsTime
        val startTime = jsonLd?.startTime ?: htmlStartTime

        // Image: prefer JSON-LD (full resolution), fall back to HTML data-src
        val imageUrl = jsonLd?.imageUrl ?: parseImageUrl(detail)

        // Ticket URL: prefer JSON-LD offers, fall back to HTML ticket link
        val htmlTicketUrl = detail?.hrefAt("a.ticketlink")
        val ticketUrl = jsonLd?.ticketUrl ?: htmlTicketUrl

        // --- HTML-only fields (not available in JSON-LD) ---

        // Event type from the dedicated row (not the desktop genre line)
        val eventTypeText = header.textAt(".event_typ")
        val eventType = mapEventType(eventTypeText)

        // Genre from the desktop "typ" div (shows genre in desktop, type in mobile)
        val genre = header.textAt(".typ.typdesktop")

        // Subtitle / tour name
        val subtitle = header.textAt("span.untertitel")?.takeIf { it.isNotBlank() }

        // Status from label element (sold out, rescheduled, cancelled)
        val statusLabel = header.textAt(".label.notice")?.lowercase().orEmpty()
        val soldOut =
            statusLabel.contains("ausverkauft") || statusLabel.contains("sold out") ||
                (detail?.selectFirst(".tickets_vkk.soldout") != null)
        val status = parseStatus(statusLabel)

        // Prices
        val (pricePresale, priceBoxOffice, priceNote) = parsePrices(detail)

        // Description (paragraphs in the content section, excluding genre paragraph)
        val description = parseDescription(detail)

        // Promoter name from the "Örtlicher Veranstalter" section
        val promoters = parsePromoterName(detail)?.let { listOf(it) }.orEmpty()

        // Artists extraction — for concerts the title is the headliner (support
        // acts come from the subtitle's "Support: <name>" pattern); parties and
        // festivals extract none. See buildArtistsForEventType.
        val artists = buildArtistsForEventType(title, subtitle, eventType)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = description,
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = imageUrl,
            sourceUrl = eventUrl,
            sourceId = "${EventSource.PRIVATCLUB.sourceIdPrefix}$slug",
            ticketUrl = ticketUrl,
            genre = genre,
            pricePresale = pricePresale,
            priceBoxOffice = priceBoxOffice,
            priceNote = priceNote,
            soldOut = soldOut,
            status = status,
            artists = artists,
            promoters = promoters
        )
    }

    // -- JSON-LD parsing --------------------------------------------------

    /**
     * Extracts structured event data from the JSON-LD block following the event wrapper.
     *
     * The JSON-LD `<script type="application/ld+json">` is a sibling element
     * containing a schema.org `MusicEvent` object with fields:
     * - `startDate` — ISO 8601 date+time (e.g. `"2026-05-16T20:00"`)
     * - `doorTime` — HH:mm doors time (e.g. `"19:00"`)
     * - `image` — full resolution image URL
     * - `url` — canonical event detail page URL
     * - `offers[].url` — first ticket shop URL
     *
     * The block is parsed with Jackson rather than by regex: proper JSON parsing
     * handles escaping, whitespace, and field ordering, and reads the ticket URL
     * from the structured `offers[].url` instead of a substring search that could
     * mismatch the event's own top-level `url`. Returns `null` if no JSON-LD block
     * is found or it cannot be parsed.
     */
    @Suppress("ReturnCount") // Guard clauses for the missing and unparseable JSON-LD block are clearer than nesting.
    private fun parseJsonLd(wrapper: Element): JsonLdData? {
        val jsonLdScript =
            wrapper
                .nextElementSiblings()
                .firstOrNull { it.tagName() == "script" && it.attr("type") == "application/ld+json" }
                ?: return null

        val eventNode = parseEventNode(jsonLdScript.data()) ?: return null

        val startDateStr = eventNode.stringOrNull("startDate")
        return JsonLdData(
            eventDate = startDateStr?.let { parseIsoDate(it) },
            startTime = startDateStr?.let { parseIsoTime(it) },
            doorsTime = eventNode.stringOrNull("doorTime")?.let { parseTime(it) },
            imageUrl = eventNode.stringOrNull("image")?.takeIf { it.startsWith("http") },
            url = eventNode.stringOrNull("url")?.takeIf { it.startsWith("http") },
            ticketUrl = extractOfferUrl(eventNode)
        )
    }

    /**
     * Parses the JSON-LD block and returns the event object node, or null if unparseable.
     *
     * Privatclub emits a single schema.org `MusicEvent` object per block; an array or
     * `@graph` wrapper is handled defensively by taking the first object node.
     */
    @Suppress(
        "TooGenericExceptionCaught", // A malformed block must degrade to null, never abort the import.
        "ReturnCount" // Guard clause for the unparseable body is clearer than nesting.
    )
    private fun parseEventNode(json: String): JsonNode? {
        val root =
            try {
                jsonMapper.readTree(json)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Privatclub JSON-LD block" }
                return null
            }
        val candidates =
            when {
                root.isArray -> root.toList()
                root.path("@graph").isArray -> root.path("@graph").toList()
                else -> listOf(root)
            }
        return candidates.firstOrNull { it.isObject }
    }

    /** Reads the first ticket-shop URL from the structured JSON-LD `offers[].url`, or null when absent. */
    private fun extractOfferUrl(eventNode: JsonNode): String? {
        val offers = eventNode.path("offers")
        val offerNodes = if (offers.isArray) offers.toList() else listOf(offers)
        return offerNodes
            .asSequence()
            .mapNotNull { it.stringOrNull("url") }
            .firstOrNull { it.startsWith("http") }
    }

    // -- HTML fallback parsers --------------------------------------------

    /**
     * Extracts the event slug from the URL path.
     *
     * Uses [URI] for robust path extraction — works regardless of scheme,
     * host, or port, unlike manual string prefix stripping.
     */
    private fun extractSlug(url: String): String = URI(url).path.removePrefix("/event/").trimEnd('/')

    /**
     * Fallback date parser using the HTML `.datum` element.
     *
     * The HTML shows dates like "Sa. 16." (part1) and "Mai" (part2).
     * The day number is extracted via regex from part1, then combined
     * with the German month name and parsed using [GERMAN_DATE_FORMATTER].
     * Without the year, we assume the current or next year based on whether
     * the date has already passed. This is only used if JSON-LD is unavailable.
     */
    @Suppress("ReturnCount") // Null-safe early exits for each date component are clearer than nested let-chains
    private fun parseDateFromHtml(header: Element): LocalDate? {
        val datumPart1 = header.textAt(".datum_part1") ?: return null // e.g. "Sa. 16."
        val datumPart2 = header.textAt(".datum_part2") ?: return null // e.g. "Mai"

        val day = DAY_NUMBER_PATTERN.find(datumPart1)?.value ?: return null
        val monthDay =
            try {
                MonthDay.parse("$day. ${datumPart2.trim()}", GERMAN_DATE_FORMATTER)
            } catch (_: DateTimeParseException) {
                return null
            }

        // Without a year, assume the nearest future occurrence
        val now = LocalDate.now(clock)
        val candidate = monthDay.atYear(now.year)
        return if (candidate.isBefore(now)) candidate.plusYears(1) else candidate
    }

    /**
     * Maps the status label text to an event status string.
     */
    private fun parseStatus(statusLabel: String): String =
        when {
            statusLabel.contains("abgesagt") || statusLabel.contains("canceled") -> "CANCELLED"
            statusLabel.contains("verschoben") || statusLabel.contains("rescheduled") -> "POSTPONED"
            statusLabel.contains("verlegt") || statusLabel.contains("relocated") -> "RELOCATED"
            else -> "SCHEDULED"
        }

    /**
     * Parses doors and start times from the HTML detail section.
     *
     * Times are rendered as "Einlass: 19:00 Beginn: 20:00" inside `.zeit_einlass`.
     * Falls back to the header's `.einlass` span if detail section is missing.
     * Used as fallback when JSON-LD times are not available.
     */
    private fun parseTimes(
        detail: Element?,
        header: Element
    ): Pair<LocalTime?, LocalTime?> {
        val zeitText = detail?.textAt(".zeit_einlass").orEmpty()

        // Extract "Einlass: HH:mm" and "Beginn: HH:mm" from the combined text
        val doorsMatch = EINLASS_PATTERN.find(zeitText)
        val startMatch = BEGINN_PATTERN.find(zeitText)

        val doorsTime =
            parseTime(doorsMatch?.groupValues?.get(1))
                ?: parseTime(header.textAt(".einlass")) // Fallback: header shows doors time
        val startTime = parseTime(startMatch?.groupValues?.get(1))

        return doorsTime to startTime
    }

    /**
     * Extracts the event image URL from the banner's `data-src` attribute.
     *
     * The WordPress theme uses lazy loading — `src` contains a tiny thumbnail
     * and `data-src` holds the full resolution image URL.
     * Used as fallback when JSON-LD image is not available.
     */
    private fun parseImageUrl(detail: Element?): String? =
        detail
            ?.attrAt(".banner img.desktop[data-src]", "data-src")
            ?.takeIf { it.startsWith("http") }

    /**
     * Parses structured prices from the ticket/entry section.
     *
     * Privatclub uses several pricing patterns:
     * - `"Tickets: 25€ (Early Bird) + 30€ (Standard)"` → presale
     * - `"AK: 35€"` → box office
     * - `"Eintritt: 4€ - ab 24h 6€"` → complex pricing → priceNote
     *
     * Returns a triple of (presale, boxOffice, priceNote).
     */
    private fun parsePrices(detail: Element?): Triple<BigDecimal?, BigDecimal?, String?> {
        if (detail == null) return Triple(null, null, null)

        var pricePresale: BigDecimal? = null
        var priceBoxOffice: BigDecimal? = null
        var priceNote: String? = null

        // Box office price from ".tickets_ak" element
        val akText = detail.textAt(".tickets_ak")
        if (akText != null) {
            val allPrices = PRICE_PATTERN.findAll(akText).toList()
            if (allPrices.size == 1 && !akText.contains("-") && !akText.contains("ab ")) {
                // Simple single price (e.g. "AK: 35€") → box office
                priceBoxOffice = extractFirstPrice(akText)
            } else {
                // Complex/conditional pricing (e.g. "Eintritt: 4€ - ab 24h 6€") → store as note
                priceNote = akText.replace(Regex("""^[^:]*:\s*"""), "").trim()
            }
        }

        // Presale price from the tickets section text
        val vkkDiv = detail.selectFirst(".tickets_vkk")
        if (vkkDiv != null && !vkkDiv.hasClass("soldout")) {
            // Look for pricing text in the linkbar area above ticket links.
            // Uses .closest() to find the enclosing .flex_wrapper instead of
            // fragile parent-chain navigation (vkkDiv sits inside
            // .linkbar > .flex_wrapper.ticketlinks > .flex > .tickets_vkk).
            val linkbarText = detail.selectFirst(".linkbar")?.ownText().orEmpty()
            val ticketText = vkkDiv.closest(".flex_wrapper")?.text().orEmpty()
            val combinedText = "$linkbarText $ticketText"

            // Try to find a VVK/presale price
            val vkkPrice = extractFirstPrice(combinedText.substringBefore("AK"))
            if (vkkPrice != null && vkkPrice != priceBoxOffice) {
                pricePresale = vkkPrice
            }
        }

        return Triple(pricePresale, priceBoxOffice, priceNote)
    }

    /**
     * Extracts the first numeric price from a text string.
     *
     * Handles German price formats: "25€", "25,00€", "25.00€", "25 €".
     * Returns null for complex/multi-price strings or when no price is found.
     */
    private fun extractFirstPrice(text: String): BigDecimal? {
        val match = PRICE_PATTERN.find(text) ?: return null
        val priceStr = match.groupValues[1].replace(",", ".")
        return try {
            BigDecimal(priceStr)
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Extracts the event description from the content section.
     *
     * Selects all `<p>` elements in the `.content` div, excluding the genre
     * paragraph (identified by class `genre`), the promoter section, and
     * status/ticketing notices.
     */
    @Suppress("ReturnCount") // Guard clauses for null detail/content are clearer than nesting
    private fun parseDescription(detail: Element?): String? {
        if (detail == null) return null
        val content = detail.selectFirst(".zeile.content") ?: return null

        val paragraphs =
            content
                .select("p")
                .filter { p ->
                    !p.hasClass("genre") &&
                        !p.hasClass("genremobile") &&
                        p.closest(".veranstaltertext") == null
                }.map { it.text().trim() }
                .filter { it.isNotBlank() }

        return paragraphs.joinToString("\n").takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the promoter name from the "Örtlicher Veranstalter" section.
     *
     * The promoter is rendered as a link inside `.veranstaltertext`, or as
     * plain text prefixed with "präsentiert von" in the header area.
     */
    @Suppress("ReturnCount") // Multiple fallback strategies with early returns are clearer than nested conditionals
    private fun parsePromoterName(detail: Element?): String? {
        if (detail == null) return null

        // Primary: "Örtlicher Veranstalter: <link>" in the content section
        val veranstalterLink = detail.selectFirst(".veranstaltertext a")
        if (veranstalterLink != null) {
            return veranstalterLink.text().trim().takeIf { it.isNotBlank() }
        }

        // Fallback: plain text after "Örtlicher Veranstalter:"
        val veranstalterText = detail.textAt(".veranstaltertext")
        if (veranstalterText != null) {
            return veranstalterText
                .removePrefix("Örtlicher Veranstalter:")
                .trim()
                .takeIf { it.isNotBlank() }
        }

        // Fallback: "präsentiert von" in the header
        val presentedBy = detail.textAt(".presentedbytext")
        if (presentedBy != null) {
            return presentedBy
                .removePrefix("präsentiert von")
                .trim()
                .takeIf { it.isNotBlank() }
        }

        return null
    }

    /**
     * Structured data extracted from a JSON-LD `MusicEvent` block.
     *
     * Used as the primary source for fields that have reliable structured
     * representations in JSON-LD, with HTML elements as fallback.
     */
    private data class JsonLdData(
        val eventDate: LocalDate?,
        val startTime: LocalTime?,
        val doorsTime: LocalTime?,
        val imageUrl: String?,
        val url: String?,
        val ticketUrl: String?
    )

    companion object {
        /** Regex to extract day number from date text like "Sa. 16." */
        private val DAY_NUMBER_PATTERN = Regex("""\d+""")

        /** Regex to extract "Einlass: HH:mm" from times text. */
        private val EINLASS_PATTERN = Regex("""Einlass:\s*(\d{1,2}:\d{2})""")

        /** Regex to extract "Beginn: HH:mm" from times text. */
        private val BEGINN_PATTERN = Regex("""Beginn:\s*(\d{1,2}:\d{2})""")

        /** Regex to extract the first price value (e.g. "25€", "25,50 €", "25.00€"). */
        private val PRICE_PATTERN = Regex("""(\d+(?:[.,]\d{1,2})?)\s*€""")

        /**
         * Formatter for parsing German month names (e.g. "16. Mai" → May 16).
         *
         * Uses [Locale.GERMAN] to recognize full German month names (Januar,
         * Februar, März, …). Case-insensitive to handle any capitalization
         * variant from the HTML source.
         */
        private val GERMAN_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("d. MMMM")
                .toFormatter(Locale.GERMAN)
    }
}
