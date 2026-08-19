package de.norm.events.scraper.delphi

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pure HTML parser for the Theater im Delphi `/programm/` page — the venue's whole upcoming
 * programme, rendered in one WordPress page with no pagination and no archive.
 *
 * The page is a run of `h2.month` headings ("August 2026"), each followed by a
 * `table.program_table` whose every `<tr>` is **one performance**: the heading supplies the month
 * and year, the row supplies the day, the clock, the poster, the genre labels, a teaser and a link
 * to its production page (`?prod=<id>`), plus its own ticket-shop link where the venue sells one.
 * A production that runs several nights repeats its row once per date, so this parser emits one
 * event per performance, not per production.
 *
 * Prices and the free-entry flag come from [parseDelphiEventRecords] — the page leaks a
 * `var_dump()` of each performance's database row into an HTML comment, and that leak is the only
 * place either fact appears. It is joined on `(production id, start time)` and is deliberately
 * best-effort: everything load-bearing is read from the rendered markup, so the day the venue
 * fixes the leak these events simply lose their prices.
 *
 * The venue's own labels are **formats**, not musical genres — `Tanz`, `Theater`, `Dialog & Lesung`
 * — so they drive the event type. Only the two that do name a genre ([MUSIC_GENRE_LABELS]) are also
 * stored as one.
 *
 * @see DelphiProductionPageScraper for the per-production page (full description, bigger poster).
 * @see DelphiWebsiteImporter for the HTTP fetch orchestrator.
 */
class DelphiProgrammePageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every performance row on the programme page.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve production links.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val records = parseDelphiEventRecords(document)

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed rows without aborting the import
        val events =
            document.select("h2.month").flatMap { heading ->
                val month = parseMonthHeading(heading.text())
                if (month == null) {
                    logger.warn { "Delphi month heading '${heading.text()}' is not a month, skipping its rows" }
                    emptyList()
                } else {
                    rowsUnder(heading).mapNotNull { row ->
                        try {
                            parseRow(row, month, baseUrl, records)
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to parse Delphi performance row, skipping" }
                            null
                        }
                    }
                }
            }
        logger.info { "Found ${events.size} performance(s) on the Delphi programme page" }
        return events
    }

    /** The performance rows belonging to a month heading — the next table rendered after it. */
    private fun rowsUnder(heading: Element): List<Element> =
        generateSequence(heading.nextElementSibling()) { it.nextElementSibling() }
            .takeWhile { !it.hasClass(MONTH_HEADING_CLASS) }
            .firstOrNull { it.`is`("table.program_table") }
            ?.select("tr")
            .orEmpty()
            .toList()

    /**
     * Parses a single performance row into a [ScrapedEvent], or `null` when it names no production
     * or no title.
     */
    @Suppress("ReturnCount") // Guard clauses for the required link/title/day are clearer than nesting
    private fun parseRow(
        row: Element,
        month: LocalDate,
        baseUrl: String,
        records: Map<String, DelphiEventRecord>
    ): ScrapedEvent? {
        val href = row.attrAt("h3.eventTitel a", "href") ?: return null
        val productionId = PRODUCTION_ID_PATTERN.find(href)?.groupValues?.get(1) ?: return null
        // The venue links its own `index.php?prod=…`, which redirects; store where it lands.
        val sourceUrl = resolveUrl(baseUrl, "?prod=$productionId")

        val title = row.textAt("h3.eventTitel")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Delphi row for production '$productionId' has no title, skipping" }
            return null
        }
        val day = row.textAt(".eventHeader h3 big")?.toIntOrNull() ?: return null
        val eventDate = runCatching { month.withDayOfMonth(day) }.getOrNull() ?: return null
        val startTime = parseTime(row.textAt(".eventHeader p")?.substringBefore(CLOCK_SUFFIX)?.trim())

        val labels = labelsOf(row)
        val eventType = labels.firstNotNullOfOrNull { mapEventType(it, DELPHI_CATEGORY_SYNONYMS) }
        // The record dump is keyed by the start time, so a row without a clock cannot be joined.
        val record = startTime?.let { records[delphiPerformanceKey(productionId, LocalDateTime.of(eventDate, it))] }

        return ScrapedEvent(
            title = title,
            description = row.textAt("p.teaserText"),
            eventType = eventType,
            eventDate = eventDate,
            startTime = startTime,
            imageUrl = row.imgSrcAt("img.listBild"),
            sourceUrl = sourceUrl,
            // Each row links its own shop (Reservix, Eventim, the act's own site); a free date
            // renders a `.kein-ticket-link` placeholder instead, which carries no href.
            ticketUrl = row.hrefAt("a.ticket-link"),
            // A production repeats its slug across dates, so the clock is part of the identity:
            // several of them play a matinee and an evening on the same day.
            sourceId = "${EventSource.THEATER_IM_DELPHI.sourceIdPrefix}$productionId/$eventDate-${startTime ?: NO_CLOCK}",
            genre = labels.filter { it in MUSIC_GENRE_LABELS }.joinToString(", ").takeIf { it.isNotBlank() },
            pricePresale = record?.pricePresale,
            priceNote = record?.priceNote,
            free = record?.free == true,
            artists = buildArtistsForEventType(title, subtitle = null, eventType = eventType)
        )
    }

    /**
     * The venue's category labels for a row, `<br>`-separated in a single cell
     * ("Musiktheater", "Musical & Show").
     *
     * Read as the cell's text nodes rather than by splitting its markup: the `<br>`s already
     * separate them, and the labels carry HTML entities (`Musical &amp; Show`) that must be
     * decoded before they can be matched against a label table.
     */
    private fun labelsOf(row: Element): List<String> =
        row
            .selectFirst("span.hideOnMobile")
            ?.textNodes()
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /**
     * Parses a `"August 2026"` month heading into the first of that month, or `null` when the
     * heading is not one. The German month names are read through their three-letter prefix, which
     * is the abbreviation the shared parser knows.
     */
    private fun parseMonthHeading(text: String): LocalDate? {
        val parts = text.trim().split(WHITESPACE_PATTERN)
        val month = parts.getOrNull(0)?.take(3)?.let { parseGermanMonthAbbreviation(it) }
        val year = parts.getOrNull(1)?.toIntOrNull()
        return if (month == null || year == null) {
            null
        } else {
            runCatching { LocalDate.of(year, month, 1) }.getOrNull()
        }
    }
}

/** Reads an attribute off the first matching child, used where a link may be relative. */
private fun Element.attrAt(
    cssQuery: String,
    attribute: String
): String? = selectFirst(cssQuery)?.attr(attribute)?.takeIf { it.isNotBlank() }

/**
 * The venue's format labels, mapped onto the model's types. The model has no dance or theatre
 * type, so a staged performance of either kind is a [EventType.SHOW] — the same call the AEG
 * venues make for their ballet. `Dialog & Lesung` is a talk or reading, and the two music labels
 * are concerts.
 */
private val DELPHI_CATEGORY_SYNONYMS =
    mapOf(
        "tanz" to EventType.SHOW.name,
        "theater" to EventType.SHOW.name,
        "musiktheater" to EventType.SHOW.name,
        "musical & show" to EventType.SHOW.name,
        "dialog & lesung" to EventType.READING.name,
        "kammermusik" to EventType.CONCERT.name,
        "elektronische musik" to EventType.CONCERT.name
    )

/**
 * The labels that name a musical genre rather than a staging format. Only these become genre tags;
 * filing `Tanz` or `Theater` as a genre would put formats into a vocabulary of musical styles.
 */
private val MUSIC_GENRE_LABELS = setOf("Kammermusik", "Elektronische Musik")

/** The class marking a month heading, used to bound the search for its table. */
private const val MONTH_HEADING_CLASS = "month"

/** Captures the production id out of a `?prod=<id>` link. */
private val PRODUCTION_ID_PATTERN = Regex("""[?&]prod=(\d+)""")

private val WHITESPACE_PATTERN = Regex("""\s+""")

/** The trailing `Uhr` the venue appends to its start time. */
private const val CLOCK_SUFFIX = "Uhr"

/** Stands in for the clock in a `sourceId` when the venue has announced no start time. */
private const val NO_CLOCK = "tba"
