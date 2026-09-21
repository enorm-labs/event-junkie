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
 * Pure HTML parser for Privatclub Berlin's WordPress event overview page.
 *
 * One page (`/`) lists every upcoming event with its details expanded inline
 * (description, prices, ticket link, promoter). Each event links to a detail page
 * (e.g. `/event/sean-rowe-2/`), but nothing there is missing here, so none is fetched.
 *
 * A `<script type="application/ld+json">` block with schema.org `MusicEvent` data
 * follows each event. JSON-LD is the primary source for the structured fields
 * (`startDate`, `doorTime`, `image`, `url`, ticket `offers`); HTML is the fallback
 * and the only source of genre, subtitle, description, prices, status and promoter.
 *
 * @see PrivatclubWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://privatclub-berlin.de/">Privatclub Berlin</a>
 */
@Suppress("TooManyFunctions")
class PrivatclubOverviewPageScraper(
    /** Clock for the year-rollover fallback; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

    /**
     * Parses all events: each lives in `.event_wrapper.skewed`, followed by its JSON-LD script.
     *
     * @param baseUrl the URL the document was fetched from, for resolving relative links.
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
     * Parses one `.event_wrapper` into a [ScrapedEvent]: JSON-LD first (date, doors, image,
     * URL, ticket URL), HTML as fallback and for title, subtitle, genre, description, prices,
     * status, promoter, artists.
     */
    @Suppress("CyclomaticComplexity", "CyclomaticComplexMethod", "ReturnCount", "LongMethod") // Cohesive single-event parsing with many optional fields
    private fun parseEventWrapper(
        wrapper: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val header = wrapper.selectFirst("a.event_header") ?: return null
        val detail = wrapper.selectFirst(".event_detail")

        // JSON-LD first
        val jsonLd = parseJsonLd(wrapper)

        // Title is required and not reliably clean in JSON-LD
        val title = header.textAt("span.titel")
        if (title.isNullOrBlank()) {
            logger.warn { "Event wrapper has no title, skipping" }
            return null
        }

        val href = header.attr("href")
        val eventUrl =
            jsonLd?.url
                ?: href.takeIf { it.isNotBlank() }?.let { resolveUrl(baseUrl, it) }
        if (eventUrl.isNullOrBlank()) {
            logger.warn { "Event '$title' has no URL, skipping" }
            return null
        }
        val slug = extractSlug(eventUrl)

        val eventDate = jsonLd?.eventDate ?: parseDateFromHtml(header)
        if (eventDate == null) {
            logger.warn { "Could not parse event date for '$title', skipping" }
            return null
        }

        val (htmlDoorsTime, htmlStartTime) = parseTimes(detail, header)
        val doorsTime = jsonLd?.doorsTime ?: htmlDoorsTime
        val startTime = jsonLd?.startTime ?: htmlStartTime

        // JSON-LD image is full resolution
        val imageUrl = jsonLd?.imageUrl ?: parseImageUrl(detail)

        val htmlTicketUrl = detail?.hrefAt("a.ticketlink")
        val ticketUrl = jsonLd?.ticketUrl ?: htmlTicketUrl

        // HTML-only fields

        // The dedicated row, not the desktop genre line
        val eventTypeText = header.textAt(".event_typ")
        val eventType = mapEventType(eventTypeText)

        // Desktop "typ" div shows the genre; mobile shows the type
        val genre = header.textAt(".typ.typdesktop")

        val subtitle = header.textAt("span.untertitel")?.takeIf { it.isNotBlank() }

        // sold out, rescheduled, cancelled
        val statusLabel = header.textAt(".label.notice")?.lowercase().orEmpty()
        val soldOut =
            statusLabel.contains("ausverkauft") || statusLabel.contains("sold out") ||
                (detail?.selectFirst(".tickets_vkk.soldout") != null)
        val status = parseStatus(statusLabel)

        val (pricePresale, priceBoxOffice, priceNote) = parsePrices(detail)

        val description = parseDescription(detail)

        // "Örtlicher Veranstalter"
        val promoters = parsePromoterName(detail)?.let { listOf(it) }.orEmpty()

        // Concerts: title is the headliner, support from the subtitle's "Support: <name>";
        // parties and festivals extract none. See buildArtistsForEventType.
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
     * Reads the schema.org `MusicEvent` from the sibling JSON-LD script: `startDate`
     * (ISO 8601 date+time, e.g. `"2026-05-16T20:00"`), `doorTime` (`"19:00"`), `image`
     * (full resolution), `url` (canonical detail page), `offers[].url` (first ticket shop).
     *
     * Parsed with Jackson, not regex: escaping, whitespace and field order are handled, and
     * the ticket URL comes from `offers[].url` rather than a substring search that could hit
     * the event's own top-level `url`. Null when no block is found or it does not parse.
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
     * Returns the event object node, or null. One `MusicEvent` per block; an array or
     * `@graph` wrapper yields its first object node.
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

    /** First ticket-shop URL from `offers[].url`, or null. */
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
     * Event slug from the URL path via [URI], so scheme, host and port do not matter.
     */
    private fun extractSlug(url: String): String = URI(url).path.removePrefix("/event/").trimEnd('/')

    /**
     * Fallback date from `.datum`: "Sa. 16." (part1) and "Mai" (part2). Day number by
     * regex, month by [GERMAN_DATE_FORMATTER], year = current or next depending on
     * whether the date has passed. Only used without JSON-LD.
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

        // Nearest future occurrence
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
     * Doors and start from "Einlass: 19:00 Beginn: 20:00" in `.zeit_einlass`, else the
     * header's `.einlass` span. Fallback when JSON-LD has no times.
     */
    private fun parseTimes(
        detail: Element?,
        header: Element
    ): Pair<LocalTime?, LocalTime?> {
        val zeitText = detail?.textAt(".zeit_einlass").orEmpty()

        val doorsMatch = EINLASS_PATTERN.find(zeitText)
        val startMatch = BEGINN_PATTERN.find(zeitText)

        val doorsTime =
            parseTime(doorsMatch?.groupValues?.get(1))
                ?: parseTime(header.textAt(".einlass")) // Fallback: header shows doors time
        val startTime = parseTime(startMatch?.groupValues?.get(1))

        return doorsTime to startTime
    }

    /**
     * Image from the banner's `data-src`: the theme lazy-loads, `src` is a tiny thumbnail.
     * Fallback when JSON-LD has no image.
     */
    private fun parseImageUrl(detail: Element?): String? =
        detail
            ?.attrAt(".banner img.desktop[data-src]", "data-src")
            ?.takeIf { it.startsWith("http") }

    /**
     * Prices from the ticket/entry section:
     * - `"Tickets: 25€ (Early Bird) + 30€ (Standard)"` → presale
     * - `"AK: 35€"` → box office
     * - `"Eintritt: 4€ - ab 24h 6€"` → priceNote
     *
     * Returns (presale, boxOffice, priceNote).
     */
    private fun parsePrices(detail: Element?): Triple<BigDecimal?, BigDecimal?, String?> {
        if (detail == null) return Triple(null, null, null)

        var pricePresale: BigDecimal? = null
        var priceBoxOffice: BigDecimal? = null
        var priceNote: String? = null

        // ".tickets_ak"
        val akText = detail.textAt(".tickets_ak")
        if (akText != null) {
            val allPrices = PRICE_PATTERN.findAll(akText).toList()
            if (allPrices.size == 1 && !akText.contains("-") && !akText.contains("ab ")) {
                // Single price ("AK: 35€") → box office
                priceBoxOffice = extractFirstPrice(akText)
            } else {
                // Conditional pricing ("Eintritt: 4€ - ab 24h 6€") → note
                priceNote = akText.replace(Regex("""^[^:]*:\s*"""), "").trim()
            }
        }

        val vkkDiv = detail.selectFirst(".tickets_vkk")
        if (vkkDiv != null && !vkkDiv.hasClass("soldout")) {
            // Pricing text sits in the linkbar above the ticket links; `.closest()` finds the
            // enclosing .flex_wrapper (vkkDiv is at .linkbar > .flex_wrapper.ticketlinks > .flex > .tickets_vkk).
            val linkbarText = detail.selectFirst(".linkbar")?.ownText().orEmpty()
            val ticketText = vkkDiv.closest(".flex_wrapper")?.text().orEmpty()
            val combinedText = "$linkbarText $ticketText"

            val vkkPrice = extractFirstPrice(combinedText.substringBefore("AK"))
            if (vkkPrice != null && vkkPrice != priceBoxOffice) {
                pricePresale = vkkPrice
            }
        }

        return Triple(pricePresale, priceBoxOffice, priceNote)
    }

    /**
     * First numeric price from "25€", "25,00€", "25.00€", "25 €"; null for multi-price
     * strings or no price.
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
     * Description: every `<p>` in `.content` except the `genre` paragraph, the promoter
     * section and status/ticketing notices.
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
     * Promoter: a link inside `.veranstaltertext`, or plain text after "präsentiert von" in the header.
     */
    @Suppress("ReturnCount") // Multiple fallback strategies with early returns are clearer than nested conditionals
    private fun parsePromoterName(detail: Element?): String? {
        if (detail == null) return null

        // "Örtlicher Veranstalter: <link>" in the content section
        val veranstalterLink = detail.selectFirst(".veranstaltertext a")
        if (veranstalterLink != null) {
            return veranstalterLink.text().trim().takeIf { it.isNotBlank() }
        }

        // Plain text after "Örtlicher Veranstalter:"
        val veranstalterText = detail.textAt(".veranstaltertext")
        if (veranstalterText != null) {
            return veranstalterText
                .removePrefix("Örtlicher Veranstalter:")
                .trim()
                .takeIf { it.isNotBlank() }
        }

        // "präsentiert von" in the header
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
     * Structured fields from a JSON-LD `MusicEvent` block; HTML is the fallback.
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
        /** Day number from "Sa. 16." */
        private val DAY_NUMBER_PATTERN = Regex("""\d+""")

        /** "Einlass: HH:mm" */
        private val EINLASS_PATTERN = Regex("""Einlass:\s*(\d{1,2}:\d{2})""")

        /** "Beginn: HH:mm" */
        private val BEGINN_PATTERN = Regex("""Beginn:\s*(\d{1,2}:\d{2})""")

        /** First price value ("25€", "25,50 €", "25.00€"). */
        private val PRICE_PATTERN = Regex("""(\d+(?:[.,]\d{1,2})?)\s*€""")

        /**
         * German month names ("16. Mai" → May 16), [Locale.GERMAN], case-insensitive.
         */
        private val GERMAN_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("d. MMMM")
                .toFormatter(Locale.GERMAN)
    }
}
