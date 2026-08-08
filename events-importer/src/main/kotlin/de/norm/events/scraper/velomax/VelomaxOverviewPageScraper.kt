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
 * Two filters apply. Entries are kept only for the [VelomaxHall] being imported, since all three
 * halls share the page. And the venue's own `data-type` must be a **cultural** one: the arena's
 * biggest strand is handball, volleyball and basketball, and with no `SPORT` event type in the
 * model those fixtures are skipped rather than filed as `OTHER`, which would bury the concerts they
 * sit among. A hall's imported count is therefore well below what its programme page shows.
 *
 * This class performs **no I/O** — it operates solely on a pre-fetched Jsoup [Document], making it
 * easy to test with a static fixture.
 *
 * @see VelomaxDetailPageScraper for the Microdata detail pages.
 * @see <a href="https://www.velomax.de/events">Velomax events listing</a>
 */
class VelomaxOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses one hall's events from the shared listing.
     *
     * @param document the parsed Jsoup document of `/events`.
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
        val parsed =
            entries.mapNotNull { entry ->
                try {
                    parseEntry(entry, baseUrl, hall)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Velomax entry, skipping" }
                    null
                }
            }
        return collapseSameDaySessions(parsed)
    }

    /**
     * Keeps one event per show and day, dropping the later sessions of a run that plays more than
     * once in a day.
     *
     * This is forced by the model, not by the source. "Disney On Ice" plays three sessions on 13
     * March and "Berlin Tattoo" two on 7 November; each day keeps its **earliest**, since the
     * listing is chronological.
     *
     * **The slug half of the blocker is gone** — `EventUpsertService` now gives each sitting of a
     * same-day run its own slug, so Theater im Delphi and the Uber Eats Music Hall store both their
     * matinee and their evening show. What still pins this workaround is the *other* unique column:
     * `event.source_id` is `UNIQUE` too, and this scraper derives it from the listing's URL slug
     * (`sourceId = "<prefix><slug>"`), which is one page per *show*, not per session. Dropping the
     * collapse without changing that trades a duplicate-slug crash for a duplicate-`sourceId` one.
     *
     * Fixing it properly means putting the session's start time in the `sourceId` — which re-keys
     * every existing Velomax event, exactly like the Neue Zukunft recurrence change. Tracked in
     * `TODO.md` as its own item rather than smuggled in here.
     */
    private fun collapseSameDaySessions(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val kept = events.distinctBy { it.eventDate to it.title }
        val dropped = events.size - kept.size
        if (dropped > 0) logger.info { "Collapsed $dropped later same-day session(s) into their show's first performance" }
        return kept
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
            sourceId = "${hall.eventSource.sourceIdPrefix}$slug",
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
