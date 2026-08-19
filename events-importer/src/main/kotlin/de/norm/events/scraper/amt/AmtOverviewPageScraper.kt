package de.norm.events.scraper.amt

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.isPlaceholderName
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure HTML parser for a single AMT `/month/<name>` page (e.g. `/month/june`).
 *
 * Each night is a `.div-grid-dan` block carrying the full event data:
 * - `data-date` — the event date as `"June 19, 2026"` (`MMMM d, yyyy`, English), the reliable
 *   date source (it carries a four-digit year, so no weekday inference is needed);
 * - `.name` — the event title (a party/series name like `SUBSTATION`, not a performer);
 * - `.sexpos` — an optional theme tag (`[SEX POSITIV]`), stored as the subtitle;
 * - `.perf` — the DJ line, stored as the description; split into [ScrapedArtist]s only when the
 *   venue delimits them with `//` (a space-separated line cannot be split reliably), and dropped
 *   entirely when it is a `tba` placeholder;
 * - `a.div-strela` — the external ticket link (Resident Advisor / EventJet);
 * - `.text-block-4` — a tiered `min – max` price (mapped to presale / box-office); and
 * - `a[href^="/event/"]` — the detail-page link, the stable per-event identity for `sourceId`.
 *
 * Every event is typed [EventType.PARTY]: AMT is a techno club and its nights are DJ dance parties,
 * so the title is a series name rather than a headliner and is never minted as an artist.
 *
 * @see AmtWebsiteImporter for the fetch orchestration (entry page → month pages).
 */
class AmtOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from one month page.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the relative
     *   `/event/<slug>` detail links into absolute `sourceUrl` values.
     * @return one [ScrapedEvent] per night with a parseable date and title. The venue leaves
     *   recently-passed nights on the current-month page; those are dropped centrally at
     *   persistence time (`EventUpsertService`), so this parser returns every dated night as-is.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("div.div-grid-dan[data-date]")
        logger.info { "Found ${items.size} event block(s) on AMT month page $baseUrl" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed nights without aborting the whole import.
        return items.mapNotNull { item ->
            try {
                parseEvent(item, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse AMT event block, skipping" }
                null
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required date and title are clearer than nesting.
    private fun parseEvent(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val eventDate =
            parseAmtDate(item.attr("data-date")) ?: run {
                logger.warn { "Could not parse AMT date '${item.attr("data-date")}', skipping event" }
                return null
            }
        val title =
            item.textAt(".name") ?: run {
                logger.warn { "No title in AMT event block on $eventDate, skipping event" }
                return null
            }

        val detailHref = item.attrAt("a[href^=\"/event/\"]", "href")
        val slug = detailHref?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val sourceUrl = detailHref?.let { resolveUrl(baseUrl, it) } ?: baseUrl
        // Prefer the detail-page slug (stable per-event identity); fall back to date + slugified title.
        val identity = slug ?: "$eventDate-${SlugGenerator.slugify(title)}"

        val lineup = item.textAt(".perf")?.takeUnless { isPlaceholderName(it) }
        val (presale, boxOffice) = parsePrices(item.textAt(".text-block-4"))

        return ScrapedEvent(
            title = title,
            subtitle = item.textAt(".sexpos"),
            description = lineup,
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.AMT.sourceIdPrefix}$identity",
            ticketUrl = item.hrefAt("a.div-strela"),
            pricePresale = presale,
            priceBoxOffice = boxOffice,
            artists = parseDjs(lineup)
        )
    }

    /**
     * The DJ line split into performers — but only when the venue delimits them with `//`. A
     * space-separated line (`"Rubi ChrizzT Reza"`) cannot be split into names reliably, so no
     * artists are extracted from it. Placeholder/non-artist tokens are filtered out.
     */
    private fun parseDjs(lineup: String?): List<ScrapedArtist> {
        if (lineup == null || !lineup.contains(DJ_SEPARATOR)) return emptyList()
        return lineup
            .split(DJ_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "DJ") }
    }

    /** Parses the `MMMM d, yyyy` (`"June 19, 2026"`) `data-date`, returning null when absent or unparseable. */
    private fun parseAmtDate(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDate.parse(text.trim(), AMT_DATE_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Splits a tiered price cell into (presale, box-office). A range (`"10 – 20"`) maps its lower
     * tier to presale and its upper tier to box-office; a single value (`"25"`) is the presale
     * price with no box-office tier. Returns `(null, null)` when the cell is blank or carries no digits.
     */
    private fun parsePrices(text: String?): Pair<BigDecimal?, BigDecimal?> {
        if (text.isNullOrBlank()) return null to null
        val values = PRICE_NUMBER.findAll(text).map { BigDecimal(it.value.replace(",", ".")) }.toList()
        return values.getOrNull(0) to values.getOrNull(1)
    }

    companion object {
        /** English long date (`MMMM d, yyyy`) rendered in the `data-date` attribute of each event block. */
        private val AMT_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)

        /** The `//` delimiter AMT uses between DJs in the `.perf` line. */
        private const val DJ_SEPARATOR = "//"

        /** A single monetary value in a tiered price cell, accepting a German (`, `) or dot decimal separator. */
        private val PRICE_NUMBER = Regex("""\d+(?:[.,]\d{1,2})?""")
    }
}
