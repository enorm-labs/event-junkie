package de.norm.events.scraper.velomax

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Pure HTML parser for the shared Velomax `/events` listing.
 *
 * The page interleaves all three halls' programmes in one chronological run of `a.ticketWrap`
 * entries, each carrying everything needed to discover and place an event:
 *
 * ```html
 * <a class="ticketWrap velodrom concert" data-type="concert"
 *    href="https://www.velodrom.de/events/event/joji-velodrom-2026-08-29">
 *   <span class="location">Velodrom</span>
 *   … <span class="weekday">Samstag,</span><span class="day">29</span>
 *     <span class="month">Aug</span><span class="year">'26</span>
 *     <span class="begin">20:00 Uhr</span>
 *   … <span class="title">Joji</span><span class="eventSubtitle">SOLARIS<br>Support: …</span>
 *   … <span class="ticketSignal available">Im Vorverkauf erhältlich</span>
 * </a>
 * ```
 *
 * Two filters apply: entries are kept only for the [VelomaxHall] being imported, and the venue's own
 * `data-type` must be a **cultural** one. The arena's biggest strand is handball, volleyball and
 * basketball, and with no `SPORT` type in the model those fixtures are skipped rather than filed as
 * `OTHER`, which would bury the concerts they sit among — so a hall's imported count is well below
 * what its programme page shows.
 *
 * **The listing is the only per-session source, which is why the `sourceId` is built here.** A run
 * that plays more than once in a day lists each session separately but links them all to one detail
 * page — `Disney On Ice` has three entries on 13 March 2027 behind a single `…-velodrom-2027-03-13`
 * permalink — so the slug identifies the *show*, not the performance. Appending the session's start
 * time (`-1830`) is what makes each one its own event; without it `event.source_id`, which is
 * `UNIQUE`, would keep only the first.
 *
 * @see VelomaxDetailPageScraper for the Microdata detail pages.
 */
@Suppress("LongComment") // 11 of these lines are the listing entry, which is what the two filters and the session-time `sourceId` are read off.
class VelomaxOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses one hall's events from the shared listing.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve detail links.
     * @param hall the hall whose entries to keep; the other two halls' entries are skipped.
     * @return a list of [ScrapedEvent] instances for that hall, in listing order.
     */
    fun scrape(
        document: Document,
        baseUrl: String,
        hall: VelomaxHall
    ): List<ScrapedEvent> {
        val entries = document.select("a.ticketWrap[href].${hall.cssClass}")
        logger.info { "Found ${entries.size} ${hall.name} entry/entries on the Velomax listing" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed entries without aborting the whole import
        return entries.mapNotNull { entry ->
            try {
                parseEntry(entry, baseUrl, hall)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Velomax entry, skipping" }
                null
            }
        }
    }

    /** Parses one `a.ticketWrap` entry, or `null` when it is a sport fixture or lacks a title. */
    @Suppress("ReturnCount") // Guard clauses for the type filter and required title are clearer than nesting
    private fun parseEntry(
        entry: Element,
        baseUrl: String,
        hall: VelomaxHall
    ): ScrapedEvent? {
        val eventType = mapEventType(entry.attr("data-type"), VENUE_EVENT_TYPES) ?: return null

        val href = entry.attr("href").takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, EVENT_PATH_PREFIX)

        val title = entry.textAt(".event-title .title")?.let(::cleanEventTitle) ?: return null
        val subtitle = entry.textAt(".eventSubtitle")
        val signal = entry.textAt(".ticketSignal").orEmpty()
        val startTime = parseTime(TIME_PATTERN.find(entry.textAt(".begin").orEmpty())?.value)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            eventDate = parseListingDate(entry) ?: UNRESOLVED_EVENT_DATE,
            startTime = startTime,
            sourceUrl = sourceUrl,
            // One permalink, many sessions — the page slug names the *show*, so the session's start
            // time is what makes a performance its own event. `Disney On Ice` runs three sessions on
            // 13 March 2027 and `Berlin Tattoo` two on 7 November, all behind one detail page per
            // day; keyed on the slug alone, `event.source_id`'s UNIQUE constraint would keep the
            // first of each and silently drop the rest. The colon is left out (`-1830`) so the id
            // reads as one token, matching the Admiralspalast, Uber and Heimathafen scrapers. An
            // entry with no published time keeps the bare slug — nothing to disambiguate it with.
            sourceId = "${hall.eventSource.sourceIdPrefix}$slug" + startTime?.format(SOURCE_ID_TIME)?.let { "-$it" }.orEmpty(),
            soldOut = signal.contains(SOLD_OUT_SIGNAL, ignoreCase = true),
            artists = buildArtistsForEventType(title, subtitle, eventType)
        )
    }

    /**
     * Assembles the date from the entry's separate day / month / year spans — `29`, `Aug`, `'26`.
     *
     * The two-digit year carries a leading apostrophe and the month is a German abbreviation, so
     * each part is normalised and looked up separately rather than parsed from the rendered run of
     * text.
     */
    @Suppress("ReturnCount") // Guard clauses for each missing date part are clearer than nesting
    private fun parseListingDate(entry: Element): LocalDate? {
        val day = entry.textAt(".day")?.trim(',', '.', ' ')?.toIntOrNull() ?: return null
        val month = parseGermanMonthAbbreviation(entry.textAt(".month")) ?: return null
        val year = entry.textAt(".year")?.trim('\'', ' ')?.toIntOrNull() ?: return null
        return runCatching { LocalDate.of(TWENTY_FIRST_CENTURY + year, month, day) }
            .onFailure { logger.warn { "Unparseable Velomax listing date '$day $month $year'" } }
            .getOrNull()
    }

    private companion object {
        /** `18:30` → `1830`, the session marker appended to a performance's `sourceId`. */
        val SOURCE_ID_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

        /** Path prefix of a hall's event permalink, stripped to obtain the slug identity. */
        const val EVENT_PATH_PREFIX = "/events/event/"

        /**
         * The venue's own `data-type` values, mapped to the model's types. `sport` is deliberately
         * **absent**: there is no `SPORT` event type, and mapping the arena's handball, volleyball
         * and basketball fixtures to `OTHER` would bury the concerts among them. An unmapped type —
         * `sport`, or anything new — makes [mapEventType] return null and the entry is skipped.
         */
        val VENUE_EVENT_TYPES: Map<String, String> =
            mapOf(
                "concert" to EventType.CONCERT.name,
                "show" to EventType.SHOW.name
            )

        /** The ticket signal marking a sold-out event ("ausverkauft"). */
        const val SOLD_OUT_SIGNAL = "ausverkauft"

        /** The clock time inside a `20:00 Uhr` label. */
        val TIME_PATTERN = Regex("""\d{1,2}:\d{2}""")

        /** Century the listing's two-digit year (`\'26`) belongs to. */
        const val TWENTY_FIRST_CENTURY = 2000
    }
}
