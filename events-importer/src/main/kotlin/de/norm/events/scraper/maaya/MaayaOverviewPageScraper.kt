package de.norm.events.scraper.maaya

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.endOn
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Pure HTML parser for MAAYA Berlin's home-page **NEXT DATES** programme.
 *
 * The venue runs WordPress with Elementor and no events plugin at all — `/wp-json/wp/v2/types` lists
 * no event post type — so this one hand-built section is the whole source. It is anchored on the
 * `#events` id rather than Elementor's generated `elementor-element-<hash>` classes, which change on
 * every re-save.
 *
 * Four things about it shape the parser:
 * 1. **The date may or may not carry a year.** The page of 2026 wrote "Thu. 05.08.2026"; the rebuild
 *    of September 2026 writes "Sat. 19.09" and, for a two-day festival, "Sat. & Sun. 10/11.10"
 *    (#1517). A year-less date takes the occurrence nearest to today, and a card with no date at all
 *    is the venue's standing opening hours ("Tue. to Sat. from 12:00 pm to 5:00 pm"), not an event.
 * 2. **The clock mixes 24-hour readings with a real meridiem, and glues `pm` onto both.** "06:00pm"
 *    is 18:00 (the shop page agrees) and "2:00 p.m." is 14:00, whereas "16:00pm" and "23:00pm" are
 *    already 24-hour and the suffix says nothing. So the meridiem counts up to twelve and is ignored
 *    above it — except where the stated end exposes it as decoration: "11:00pm – 17:00pm" is a
 *    daytime event, because a start after its own end is not a night. The venue writes an overnight
 *    run as "until late", never as a time, so the rule has no overnight case to get wrong.
 * 3. **The weekday label is unreliable**, so it is not read: the venue writes "Thu. 05.08.2026" for a
 *    Wednesday. A year-less date therefore takes the nearest occurrence outright, not the nearest
 *    on the stated weekday, which is [inferYearForWeekday] with no weekday.
 * 4. **The button is both the ticket link and the entry note** — out to Xceed or Eventim where an
 *    event is ticketed, the venue's own wording otherwise ("FREE ENTRY"); see [entryNoteOf]. It is
 *    taken as stated, mistakes included: both halves of a two-part night can share a shop page.
 *
 * **No artists are minted.** The titles are series and party names ("SUPAFLY", "RIPPLES W/ AMINE K")
 * rather than acts, so a derived headliner would file party names in the artist table. For the same
 * reason the type falls back to `OTHER` via [inferUnmarkedTitleType] rather than `PARTY`: MAAYA is a
 * multi-format house — gallery, garden, pool and market — so a cue-less title is genuinely unknown
 * rather than presumed a club night the way Crack Bellmer's is.
 *
 * @see MAAYA_LIMITATIONS for what the venue does not publish.
 * @see MaayaWebsiteImporter for the HTTP fetch orchestrator.
 */
@Suppress("LongComment") // 30 lines, and the four numbered traps are four real ones — the missing year, the half-real `pm`, the weekday, the button.
class MaayaOverviewPageScraper(
    /** Clock for the year a year-less date is given. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every dated event out of the home page's **NEXT DATES** section.
     *
     * @param baseUrl the URL the document was fetched from; stored as every event's `sourceUrl`,
     *   since the venue publishes no per-event pages.
     * @return a list of [ScrapedEvent] instances in listing order.
     * @throws IllegalStateException when the page has no programme section at all, or when its
     *   cards state schedules and none yields a date. Either is a redesign, not an empty programme
     *   — a venue with nothing on still renders the section, with no timed line in it — and the run
     *   fails so the source reads as failing rather than as quiet. It read as quiet for a year on
     *   the missing section (#1498), then on the year-less dates (#1517). A page holding only the
     *   standing opening hours fails the same way, on purpose: that is a source worth a look too.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val section =
            checkNotNull(document.selectFirst(PROGRAMME_SECTION)) {
                "No '$PROGRAMME_ANCHOR' section on the MAAYA home page — the programme block moved or was renamed"
            }

        val cards = section.select(EVENT_CARD)
        logger.info { "Found ${cards.size} programme card(s) in the MAAYA NEXT DATES section" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the import
        val events =
            cards.mapNotNull { card ->
                try {
                    parseCard(card, baseUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse MAAYA programme card, skipping" }
                    null
                }
            }
        // A clock reading marks a schedule line; the area blurbs below the programme carry none.
        val scheduled = cards.count { TIME_PATTERN.containsMatchIn(it.textAt(SCHEDULE).orEmpty()) }
        check(events.isNotEmpty() || scheduled == 0) {
            "$scheduled MAAYA card(s) state a schedule and none yields a date — the date format changed"
        }
        return events
    }

    /**
     * Parses one programme card into a [ScrapedEvent], or `null` when it has no title or no date.
     *
     * A dateless card is the venue's standing opening hours rather than a malformed event, so it is
     * dropped quietly — logging a warning per import for the two permanent ones would be noise.
     */
    @Suppress("ReturnCount") // Guard clauses for the required title/date are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val title = card.textAt(TITLE)?.let(::cleanEventTitle)
        if (title.isNullOrBlank()) return null

        val schedule = card.textAt(SCHEDULE).orEmpty()
        val (eventDate, lastDay) = parseEventDates(schedule) ?: return null
        val (startTime, endTime) = parseTimes(schedule)
        // An end time needs its day at the persistence boundary; a stated span already has one.
        val endDate = lastDay ?: endTime?.let { endOn(eventDate, startTime, it) }

        val entryNote = entryNoteOf(card)
        return ScrapedEvent(
            title = title,
            eventType = inferUnmarkedTitleType(title),
            eventDate = eventDate,
            endDate = endDate,
            startTime = startTime,
            endTime = endTime,
            imageUrl = card.imgSrcAt(POSTER),
            // No per-event pages exist, so the listing itself is the canonical URL.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.MAAYA.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = card.hrefAt(BUTTON),
            // A bare "FREE ENTRY" is fully carried by the free flag, so storing it again as a note
            // would only repeat it; a qualified one is not, and is kept verbatim.
            priceNote = entryNote?.takeUnless { it.equals(FREE_ENTRY_LABEL, ignoreCase = true) },
            free = detectFree(priceNote = entryNote, title = title)
        )
    }

    /**
     * The venue's own entry wording, or `null` when the button says nothing about it.
     *
     * The same button serves both roles, so its label is only an entry note when it is not a bare
     * call to action: "TICKETS" and "RESERVATIONS" name the link, whereas "FREE ENTRY WITH 10€
     * VOUCHER" and "TICKETS AT THE DOOR" state the terms. The label is stored as written — the
     * venue's phrasing is the most precise thing available, since it publishes no numeric prices —
     * and the shared [detectFree] reads the free flag off it.
     */
    private fun entryNoteOf(card: Element): String? = card.textAt(BUTTON_LABEL)?.takeUnless { it.uppercase() in CALL_TO_ACTION_LABELS }

    /**
     * Reads the card's date and, for a `10/11.10` two-day span, its last day; `null` when the line
     * states no date (a standing offer). A year-less date takes the occurrence nearest to today —
     * see the class KDoc for why the weekday label is not the tiebreaker.
     */
    private fun parseEventDates(schedule: String): Pair<LocalDate, LocalDate?>? {
        val match = DATE_PATTERN.find(schedule) ?: return null
        val monthDay = MonthDay.of(match.digits("month"), match.digits("day"))
        val eventDate = match.groups["year"]?.let { monthDay.atYear(it.value.toInt()) } ?: inferYearForWeekday(monthDay, weekday = null, clock = clock)
        val endDate = match.groups["lastDay"]?.let { eventDate.withDayOfMonth(it.value.toInt()) }
        return eventDate to endDate
    }

    /** A group the pattern always fills, as a number. */
    private fun MatchResult.digits(group: String): Int = checkNotNull(groups[group]).value.toInt()

    /**
     * Reads the start and, when one is stated after `to`/`–`/`until`, the end of the schedule line.
     * The end is kept only when it follows the start on the same day; a start that lands after its
     * own end had a decorative meridiem (see the class KDoc) and is re-read without it.
     */
    private fun parseTimes(schedule: String): Pair<LocalTime?, LocalTime?> {
        val start = TIME_PATTERN.find(schedule) ?: return null to null
        val end = END_PATTERN.find(schedule, start.range.last)?.let { TIME_PATTERN.find(it.groupValues[1]) }?.let(::clockTime)
        val startTime = clockTime(start)
        return when {
            end == null -> startTime to null
            startTime <= end -> startTime to end
            else -> clockTime(start, meridiem = false) to end
        }
    }

    /** The 24-hour reading of one clock match, honouring a stated meridiem only on an hour up to twelve. */
    private fun clockTime(
        match: MatchResult,
        meridiem: Boolean = true
    ): LocalTime {
        val (hour, minute, suffix) = match.destructured
        val plain = hour.toInt()
        val resolved =
            when {
                !meridiem || plain > NOON -> plain
                suffix.startsWith('p', ignoreCase = true) && plain < NOON -> plain + NOON
                suffix.startsWith('a', ignoreCase = true) && plain == NOON -> 0
                else -> plain
            }
        return LocalTime.of(resolved, minute.toInt())
    }

    private companion object {
        /** The id Elementor puts on the NEXT DATES block — stable, unlike its generated element classes. */
        const val PROGRAMME_ANCHOR = "#events"

        /** The top-level section wrapping the NEXT DATES heading and every card below it. */
        const val PROGRAMME_SECTION = "section.elementor-top-section:has($PROGRAMME_ANCHOR)"

        /** One event card: an Elementor column inside one of the section's inner rows. */
        const val EVENT_CARD = "section.elementor-inner-section .elementor-column > .elementor-widget-wrap"

        const val TITLE = ".elementor-widget-heading .elementor-heading-title"
        const val SCHEDULE = ".elementor-widget-text-editor"
        const val POSTER = ".elementor-widget-image img"
        const val BUTTON = ".elementor-widget-button a.elementor-button"
        const val BUTTON_LABEL = ".elementor-widget-button .elementor-button-text"

        /** Button labels that name the link rather than the entry terms, so carry no pricing information. */
        val CALL_TO_ACTION_LABELS = setOf("TICKETS", "RESERVATIONS")

        /** The unqualified free-entry label, whose meaning the `free` flag already carries. */
        const val FREE_ENTRY_LABEL = "FREE ENTRY"

        /**
         * The venue's dotted date: `05.08.2026`, `19.09`, or a two-day `10/11.10`; the span's last
         * day and the year are optional.
         */
        val DATE_PATTERN = Regex("""(?<day>\d{1,2})(?:/(?<lastDay>\d{1,2}))?\.(?<month>\d{1,2})(?:\.(?<year>\d{4}))?""")

        /** One clock reading with its optional meridiem: `16:00pm`, `2:00 p.m.`, `14:00`. */
        val TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})\s*([ap]\.?m\.?)?""", RegexOption.IGNORE_CASE)

        /** What introduces the end of the range; `and from` introduces a second start and is not matched. */
        val END_PATTERN = Regex("""(?:\bto\b|\buntil\b|[–-])\s*(\d{1,2}:\d{2}\s*(?:[ap]\.?m\.?)?)""", RegexOption.IGNORE_CASE)

        const val NOON = 12
    }
}
