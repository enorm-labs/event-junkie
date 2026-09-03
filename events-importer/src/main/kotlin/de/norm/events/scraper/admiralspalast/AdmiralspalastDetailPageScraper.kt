package de.norm.events.scraper.admiralspalast

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Pure HTML parser for an Admiralspalast production page (`/veranstaltung/<slug>.html`).
 *
 * This is where the venue's actual schedule lives. The page's `#eventlist` renders one `.item` row
 * **per performance**, so a production that plays four nights yields four events:
 *
 * ```html
 * <div class="item first even">
 *   <div class="field date-time">
 *     <span class="value eventdate evDay">25</span>
 *     <span class="value eventdate evMJ">Jan 2027</span>
 *     <span class="value eventdate evWdT">Mo, 19:30</span>
 *   </div>
 *   <div class="field eventname"><img src="assets/images/8/…"></div>
 *   <div class="field city-venue"><span class="value eventname"><h2>ABBA Gold</h2></span>
 *     <span class="value eventzusatz"><h4>verlegt vom 15.09.2026</h4></span></div>
 *   <div class="field eventtix"><a href="https://www.eventim.de/…">Tickets</a></div>
 * </div>
 * ```
 *
 * The date is split across two spans and the month is a German abbreviation, so it is assembled
 * rather than parsed as one string; the weekday span carries the start time after the comma.
 *
 * @see AdmiralspalastListingPageScraper for discovery and the genre categories.
 * @see AdmiralspalastWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.admiralspalast.theater/veranstaltung/abba-gold-the-concert-show-emotion.html">Example production</a>
 */
class AdmiralspalastDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every performance the production page lists.
     *
     * @param sourceUrl the production's URL, used as [ScrapedEvent.sourceUrl] and to derive the
     *   slug half of each [ScrapedEvent.sourceId].
     * @param category the category the venue files this production under, or `null` when it appears
     *   on no filter page. It drives [ScrapedEvent.eventType] only — see [parsePerformance] for why
     *   it is deliberately not stored as the genre.
     */
    fun scrape(
        document: Document,
        sourceUrl: String,
        category: String?
    ): List<ScrapedEvent> {
        val slug = extractEventSlug(sourceUrl, PRODUCTION_PATH_PREFIX).removeSuffix(PAGE_SUFFIX)
        val rows = document.select("$EVENT_LIST .item")

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed rows without aborting the whole import
        return rows.mapNotNull { row ->
            try {
                parsePerformance(row, sourceUrl, slug, category)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse an Admiralspalast performance on $sourceUrl, skipping" }
                null
            }
        }
    }

    /**
     * Parses one `.item` performance row, or `null` when it carries no title or no usable date.
     *
     * The [category] drives [ScrapedEvent.eventType] and is deliberately **not** stored as the
     * genre. The venue's `eventkategorie` vocabulary names a staging format, not a musical style —
     * `Konzert`, `Show`, `Lesung`, `Podcast`, `Comedy`, `Tanz`, `Ballett`, `Diskussion`, `Kultur` —
     * so filing it as the genre made 96 events read as genre-tagged while carrying no style at all
     * ("Konzert" as the genre of a concert), and pushed the format labels into the genre-tag
     * vocabulary. The category is already the event *type*; the house publishes no genre anywhere,
     * so none is stored.
     */
    @Suppress("ReturnCount") // Guard clauses for the required title and date are clearer than nesting
    private fun parsePerformance(
        row: Element,
        sourceUrl: String,
        slug: String,
        category: String?
    ): ScrapedEvent? {
        val title = row.textAt(TITLE)?.let(::cleanEventTitle)
        if (title == null) {
            logger.warn { "Performance row on $sourceUrl has no title, skipping" }
            return null
        }

        val eventDate = parsePerformanceDate(row)
        if (eventDate == null) {
            logger.warn { "Performance row '$title' on $sourceUrl has no parseable date, skipping" }
            return null
        }

        val note = row.textAt(RESCHEDULE_NOTE)
        val eventType = mapEventType(category, GENRE_EVENT_TYPES) ?: EventType.SHOW.name

        val startTime = parseTime(row.textAt(WEEKDAY_AND_TIME)?.substringAfter(',')?.trim())

        return ScrapedEvent(
            title = title,
            subtitle = note,
            eventType = eventType,
            eventDate = eventDate,
            startTime = startTime,
            imageUrl = row.attrAt("img", "src")?.let { resolveUrl(siteRoot(sourceUrl), it) },
            ticketUrl = row.hrefAt("$TICKET_CELL a"),
            soldOut = row.textAt(TICKET_CELL)?.let { SOLD_OUT.containsMatchIn(it) } == true,
            status = parseScheduleNote(note),
            sourceUrl = sourceUrl,
            // One production, many nights — and on a matinee day, two performances in one night's
            // slot. The date alone is therefore not an identity: this house plays *Mamma Mia* at
            // 14:30 and 19:30 on the same Saturday, each with its own ticket link, and both would
            // arrive under one `sourceId`. `event.source_id` is `UNIQUE`, so the second is dropped
            // before it reaches the database (EventUpsertService.deduplicateScrapedEvents) — the
            // matinee simply never existed as far as the app was concerned. Appending the start
            // time makes each performance its own event; the colon is left out so the id reads as
            // one token, matching the Uber and Heimathafen scrapers.
            sourceId =
                "${EventSource.ADMIRALSPALAST.sourceIdPrefix}$slug-$eventDate" +
                    startTime?.format(SOURCE_ID_TIME)?.let { "-$it" }.orEmpty(),
            // The venue bills no lineup, so the act is whatever the production title names. The
            // subtitle is deliberately not offered as a support-act source: it only ever carries the
            // reschedule note, which would be minted as a performer.
            artists = buildArtistsForEventType(title, subtitle = null, eventType = eventType)
        )
    }

    /**
     * The site root a poster path is relative to.
     *
     * Contao writes every asset path relative (`assets/images/8/…`) and declares a page-wide
     * `<base href="https://www.admiralspalast.theater/">` to anchor them — but it emits that tag
     * **unterminated**, so it cannot be trusted to survive parsing. Resolving against the root
     * explicitly reproduces what the tag intends; resolving against the production URL instead would
     * yield `/veranstaltung/assets/…`, which 404s.
     */
    private fun siteRoot(url: String): String = URI(url).resolve("/").toString()

    /**
     * Assembles the performance date from the day and `MMM yyyy` spans the venue renders separately.
     */
    private fun parsePerformanceDate(row: Element): LocalDate? {
        val day = row.textAt(DAY)?.toIntOrNull()
        val monthAndYear = row.textAt(MONTH_AND_YEAR)?.split(WHITESPACE).orEmpty()
        val month = parseGermanMonthAbbreviation(monthAndYear.firstOrNull())
        val year = monthAndYear.getOrNull(1)?.toIntOrNull()
        if (day == null || month == null || year == null) return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /**
     * Reads the venue's reschedule note, whose two idioms mean opposite things.
     *
     * `verschoben auf <date>` sits on the **original** date, which is therefore postponed. `verlegt
     * vom <date>` sits on the **replacement** date, which is going ahead as printed — the shared
     * [parseEventStatus] reads any "verlegt" as `RELOCATED`, which would mark the one date that is
     * definitely happening as moved. Everything else is left to the shared vocabulary.
     */
    private fun parseScheduleNote(note: String?): String =
        when {
            note.isNullOrBlank() -> EventStatus.SCHEDULED.name
            MOVED_FROM.containsMatchIn(note) -> EventStatus.SCHEDULED.name
            else -> parseEventStatus(note)
        }

    private companion object {
        /** `19:30` → `1930`, the session marker appended to a performance's `sourceId`. */
        val SOURCE_ID_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

        /** Path prefix of a production page, stripped to obtain its slug. */
        const val PRODUCTION_PATH_PREFIX = "/veranstaltung/"

        /** Contao renders every page with a `.html` suffix, which is not part of the identity. */
        const val PAGE_SUFFIX = ".html"

        /** The container holding one `.item` per performance. */
        const val EVENT_LIST = "#eventlist"

        const val DAY = ".evDay"
        const val MONTH_AND_YEAR = ".evMJ"
        const val WEEKDAY_AND_TIME = ".evWdT"
        const val TITLE = ".value.eventname"
        const val RESCHEDULE_NOTE = ".value.eventzusatz"
        const val TICKET_CELL = ".field.eventtix"

        /** What the ticket cell says instead of offering a link once a performance has sold out. */
        val SOLD_OUT = Regex("""\bausverkauft\b""", RegexOption.IGNORE_CASE)

        /** "verlegt vom <date>" — this row is the replacement date, not the abandoned one. */
        val MOVED_FROM = Regex("""\bverlegt\s+vom\b""", RegexOption.IGNORE_CASE)

        /**
         * The venue's own categories, mapped onto the model's types. The house is a variety theatre,
         * so anything staged rather than played — musical, dance, magic, theatre — is a
         * [SHOW][EventType.SHOW], and the music categories collapse onto
         * [CONCERT][EventType.CONCERT]. `Kultur`, `Diskussion` and `Podcast` are deliberately absent:
         * they name a framing rather than a form, and fall through to the theatre default.
         */
        val GENRE_EVENT_TYPES: Map<String, String> =
            mapOf(
                "ausstellung" to EventType.EXHIBITION.name,
                "comedy" to EventType.SHOW.name,
                "elektro" to EventType.CONCERT.name,
                "hardrock" to EventType.CONCERT.name,
                "jazz" to EventType.CONCERT.name,
                "klassik" to EventType.CONCERT.name,
                "konzert" to EventType.CONCERT.name,
                "lesung" to EventType.READING.name,
                "musical" to EventType.SHOW.name,
                "open air" to EventType.CONCERT.name,
                "party" to EventType.PARTY.name,
                "rock/pop" to EventType.CONCERT.name,
                "schlager" to EventType.CONCERT.name,
                "show" to EventType.SHOW.name,
                "tanz/ballett" to EventType.SHOW.name,
                "theater" to EventType.SHOW.name,
                "zaubershow" to EventType.SHOW.name
            )
    }
}
