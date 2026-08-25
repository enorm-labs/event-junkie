package de.norm.events.scraper.peteredel

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistList
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractSupportFromSubtitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.peteredel.PeterEdelOverviewPageScraper.Companion.VENUE_FORMAT_KEYWORDS
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Kulturhaus Peter Edel's Umbraco events page.
 *
 * The whole programme, running more than a year out, lives on the one `/events/` page as an Umbraco
 * grid: a flat ordered stream of two kinds of child — a plain `<div>` holding an `<h1>` month heading
 * (`AUGUST 2026`), and a `div.box-rc-dark-grey` holding one event in three Bootstrap columns:
 *
 * ```
 * .col-md-2   <h3>DO | 20.08.</h3>            ← weekday + year-less day.month, then the flyer
 * .col-md-8   <h3><a href="…">TITLE</a></h3>
 *             <p>Support: Sian Able</p>       ← optional subtitle (support act, tour or format)
 *             <div class="text-block">…</div> ← description
 *             <p>Einlass: 19:00 Uhr Beginn: 20:00 Uhr Tickets: … Präsentiert von: …</p>
 *             <p><span>Unbestuhlt</span> <span>Keine Sitzplatzgarantie</span></p>
 * .col-md-2   <h3>Tickets: 25,00€</h3>        ← the clean, structured price
 *             <p>(zzgl. VVK-Gebühr) Abendkasse: 32 Euro …</p>
 * ```
 *
 * **The dates carry no year, so the month headings supply it.** The parser walks the grid in order,
 * remembering the year of the most recent heading and applying it to every box beneath it. Day and
 * month come from the box, so a row filed under the wrong heading still keeps its own date.
 *
 * **The page does not always open with a heading, and the boxes above the first one are still real
 * events** (#498). The venue retires the current month's heading as the month winds down while its
 * remaining dates stay listed. Those boxes take their year from the first heading *below* them: the
 * listing runs chronologically, so a box whose month is at or before the heading's shares its year,
 * and one after it belongs to the year before — which is what keeps a December box above a
 * `JANUAR 2027` heading in 2026. Only the month is compared, which makes this robust to the venue's
 * occasional out-of-order pair within a month. The year is still read off the page rather than
 * inferred from the clock: a box with no heading below it, or under a heading whose month will not
 * parse, is skipped rather than guessed at, because inventing a year is worse than dropping a row
 * visibly.
 *
 * Parsing decisions worth knowing:
 *  - **Prices are read from the third column, not the prose.** The middle column's `Tickets:` line is
 *    hand-written and sometimes carries a sale-start date rather than a price ("Tickets: Ab 17.04.26
 *    …"), which would parse as a number. The third column is the venue's own structured rendering and
 *    is consistent across the programme. Its text, minus the "Für mehr Infos hier klicken" call to
 *    action, is kept verbatim as [ScrapedEvent.priceNote] — which preserves the `Ab …` from-prices,
 *    the per-row tiers ("30 Euro / 35 Euro") and the `zzgl.`/`inkl. VVK-Gebühr` distinction that the
 *    two numeric fields cannot express.
 *  - **A cancelled event is marked twice** — `[Abgesagt!]` appended to the title and `Leider
 *    abgesagt!` in the ticket column. Either sets `CANCELLED`; the marker is stripped from the stored
 *    title so it stays out of the `sourceId` and the event keeps its identity if reinstated.
 *  - **Doors and start are read as a labelled pair**, written separately ("Einlass: 19:00 Uhr Beginn:
 *    20:00 Uhr") or collapsed ("Beginn & Einlass: 18:00 Uhr"), and occasionally transposed — which
 *    `orderDoorsBeforeStart` corrects centrally at the persistence boundary.
 *
 * Typing falls back to [inferUnmarkedTitleType] over title and subtitle, extended by
 * [VENUE_FORMAT_KEYWORDS] for the formats the house names in its own words. Anything else stays
 * `OTHER` rather than being guessed into `CONCERT`, which would also mint the event's name as a
 * headliner — and for the same reason [buildArtistList] takes an act only where a support billing
 * confirms one, so "Tanztee im PETER EDEL" does not become an artist. The seating badges
 * ("Bestuhlt", "Freie Platzwahl") are dropped: the data model has no field for them, see #303.
 *
 * @see PETER_EDEL_LIMITATIONS for what the venue does not publish.
 * @see PeterEdelWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.peteredel.de/events/">Kulturhaus Peter Edel</a>
 */
@Suppress("LongComment") // The grid is hand-authored rich text, and this block is the shape the parser walks.
class PeterEdelOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event from the programme page.
     *
     * @param baseUrl the URL the document was fetched from, stored as each event's
     *   [ScrapedEvent.sourceUrl] — the venue publishes no per-event page.
     * @return a list of [ScrapedEvent] instances, in listing order.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        // Scoped to the grid that actually holds events: the page renders a second, event-less
        // .grid-section whose headings must not set the running year.
        val children = document.select(".grid-section:has(> div.$EVENT_BOX_CLASS) > div")
        logger.info { "Found ${children.count { it.hasClass(EVENT_BOX_CLASS) }} event box(es) on the Peter Edel programme" }

        // The boxes above this are dated from it rather than from the heading they do not have; -1
        // when the page carries no heading at all, which leaves every box undated as before.
        val firstHeading = children.indexOfFirst { !it.hasClass(EVENT_BOX_CLASS) && headingYear(it) != null }

        var year: Int? = null
        val events = mutableListOf<ScrapedEvent>()
        for ((index, child) in children.withIndex()) {
            if (!child.hasClass(EVENT_BOX_CLASS)) {
                year = headingYear(child) ?: year
            } else {
                val boxYear = if (index < firstHeading) yearAboveFirstHeading(children[firstHeading], child) else year
                parseBoxSafely(child, boxYear, baseUrl)?.let(events::add)
            }
        }
        return events
    }

    /**
     * The year for a box sitting above the page's first month heading, read off that heading: its own
     * year when the box's month is at or before the heading's, the year before when it is after —
     * the listing runs chronologically month by month, so a December box above `JANUAR 2027` is 2026.
     *
     * `null` when either month is unreadable, which leaves the box to be dropped with the same
     * warning as any other undatable row rather than dated by a guess.
     */
    private fun yearAboveFirstHeading(
        heading: Element,
        box: Element
    ): Int? {
        val year = headingYear(heading)
        // The headings spell the month out, so the first three letters are what
        // parseGermanMonthAbbreviation recognises — true for all twelve, `MÄRZ` included.
        val headingMonth =
            heading
                .textAt("h1")
                ?.trimStart()
                ?.take(GERMAN_MONTH_PREFIX)
                ?.let(::parseGermanMonthAbbreviation)
                ?.value
        val boxMonth =
            box
                .select(".row > .column")
                .getOrNull(DATE_COLUMN)
                ?.let { DAY_MONTH.find(it.textAt("h3").orEmpty()) }
                ?.groupValues
                ?.get(2)
                ?.toInt()
        return if (year == null || headingMonth == null || boxMonth == null) {
            null
        } else if (boxMonth <= headingMonth) {
            year
        } else {
            year - 1
        }
    }

    /** The year of a `AUGUST 2026` month heading, or `null` when this child carries no heading. */
    private fun headingYear(child: Element): Int? =
        child.textAt("h1")?.let {
            HEADING_YEAR
                .find(it)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        }

    @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed boxes without aborting the whole import
    private fun parseBoxSafely(
        box: Element,
        year: Int?,
        baseUrl: String
    ): ScrapedEvent? =
        try {
            parseBox(box, year, baseUrl)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Peter Edel event box, skipping" }
            null
        }

    /** Parses one event box, or `null` when it has no title, no usable date, or no month heading above it. */
    @Suppress("ReturnCount") // Guard clauses for the required columns / title / date are clearer than nesting
    private fun parseBox(
        box: Element,
        year: Int?,
        baseUrl: String
    ): ScrapedEvent? {
        val columns = box.select(".row > .column")
        if (columns.size < MAIN_COLUMN + 1) return null
        val dateColumn = columns[DATE_COLUMN]
        val main = columns[MAIN_COLUMN].children().firstOrNull() ?: return null
        val ticketColumn = columns.getOrNull(TICKET_COLUMN)

        val heading = main.selectFirst("h3") ?: return null
        val rawTitle = heading.text().trim()
        val title = cleanEventTitle(rawTitle.replace(CANCELLED_TITLE_MARKER, ""))
        if (title.isBlank()) {
            logger.warn { "No title in Peter Edel event box, skipping" }
            return null
        }

        val eventDate = parseEventDate(dateColumn, year)
        if (eventDate == null) {
            logger.warn { "No usable date for Peter Edel event '$title' (heading year $year), skipping" }
            return null
        }

        val subtitle = subtitleOf(main)
        val info = infoParagraph(main)?.text().orEmpty()
        val (doors, start) = parseTimes(info)
        val note = priceNote(ticketColumn)
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = main.textAt(".text-block .text-container"),
            eventType = resolveEventType(title, subtitle),
            eventDate = eventDate,
            doorsTime = doors,
            startTime = start,
            imageUrl = dateColumn.attrAt("img", "src")?.let { runCatching { resolveUrl(baseUrl, it) }.getOrNull() },
            // No per-event page exists — the title links straight to the ticket shop — so every event
            // points at the listing and takes its identity from the date plus the slugified title.
            // Both are needed: a recurring format (Tanztee, AFTER WORK) reuses its title all season,
            // and up to two different events share one date.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.PETER_EDEL.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = heading.selectFirst("a")?.absUrl("href")?.takeIf { it.isNotBlank() },
            pricePresale = ticketColumn?.textAt("h3")?.let(::parseEuroAmount),
            priceBoxOffice = note?.let { BOX_OFFICE_PRICE.find(it)?.groupValues?.get(1) }?.let(::parseEuroAmount),
            priceNote = note,
            soldOut = ticketColumn?.text()?.contains(SOLD_OUT_MARKER, ignoreCase = true) == true,
            status = resolveStatus(rawTitle, ticketColumn),
            promoters =
                listOfNotNull(
                    PROMOTER
                        .find(info)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                ),
            artists = buildArtistList(title, extractSupportFromSubtitle(subtitle))
        )
    }

    /**
     * The event's date, from the box's own `DO | 20.08.` line plus the [year] of the month heading
     * above it. Returns `null` when either part is missing or the day/month is not a real date.
     */
    @Suppress("ReturnCount") // Guard clauses for the missing year / unparseable line are clearer than nesting
    private fun parseEventDate(
        dateColumn: Element,
        year: Int?
    ): LocalDate? {
        if (year == null) return null
        val match = DAY_MONTH.find(dateColumn.textAt("h3").orEmpty()) ?: return null
        return runCatching { LocalDate.of(year, match.groupValues[2].toInt(), match.groupValues[1].toInt()) }.getOrNull()
    }

    /**
     * The subtitle line — the paragraph the venue puts between the title and the description block,
     * carrying either a support billing ("Support: Sian Able"), a tour name or the format ("Lesung").
     * `null` when the event has none, in which case the first paragraph is the info line instead.
     */
    private fun subtitleOf(main: Element): String? =
        main
            .children()
            .takeWhile { !it.hasClass("text-block") }
            .firstOrNull { it.tagName() == "p" }
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /** The paragraph after the description block that carries the times, prices and promoter. */
    private fun infoParagraph(main: Element): Element? =
        main
            .children()
            .dropWhile { !it.hasClass("text-block") }
            .firstOrNull { it.tagName() == "p" && INFO_LABEL.containsMatchIn(it.text()) }

    /**
     * The (doors, start) pair from the info line.
     *
     * A collapsed `Beginn & Einlass: 18:00 Uhr` (in either word order) sets both to the same time;
     * otherwise each label is read on its own and either may be absent. A transposed pair is left as
     * written here and corrected centrally at the persistence boundary.
     */
    private fun parseTimes(info: String): Pair<LocalTime?, LocalTime?> {
        COMBINED_TIME.find(info)?.let {
            val time = parseTime(it.groupValues[1])
            return time to time
        }
        return parseTime(DOORS_TIME.find(info)?.groupValues?.get(1)) to parseTime(START_TIME.find(info)?.groupValues?.get(1))
    }

    /**
     * The venue's own ticket statement from the third column, with its "Für mehr Infos hier klicken"
     * call to action removed. Only kept when it actually states a price — the column of a cancelled
     * event reads "Leider abgesagt!", which is a status, not a pricing note.
     */
    private fun priceNote(ticketColumn: Element?): String? =
        ticketColumn
            ?.text()
            ?.replace(CALL_TO_ACTION, "")
            ?.trim()
            ?.takeIf { it.startsWith(TICKETS_LABEL, ignoreCase = true) }

    /** `CANCELLED` when either the title marker or the ticket column says so, otherwise `SCHEDULED`. */
    private fun resolveStatus(
        rawTitle: String,
        ticketColumn: Element?
    ): String {
        val cancelled =
            rawTitle.contains(CANCELLED_MARKER, ignoreCase = true) ||
                ticketColumn?.text()?.contains(CANCELLED_MARKER, ignoreCase = true) == true
        return if (cancelled) "CANCELLED" else "SCHEDULED"
    }

    private companion object {
        /** Parses a `25,00€` or `32 Euro` amount, in either decimal notation. */
        private fun parseEuroAmount(text: String): BigDecimal? =
            EURO_AMOUNT
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.let { runCatching { BigDecimal(it.replace(',', '.')) }.getOrNull() }

        /**
         * The event type, from the title and subtitle only — the venue states no category.
         *
         * A format the house names in its own words ([VENUE_FORMAT_KEYWORDS]) wins; otherwise the
         * shared [inferUnmarkedTitleType] applies its unambiguous keyword cues and falls back to
         * `OTHER`. It deliberately does not default to `CONCERT`: this is a mixed-programme
         * Kulturhaus, not a live-music room.
         */
        private fun resolveEventType(
            title: String,
            subtitle: String?
        ): String {
            val haystack = listOfNotNull(title, subtitle).joinToString(" ").lowercase()
            return VENUE_FORMAT_KEYWORDS.entries.firstOrNull { (keyword, _) -> keyword in haystack }?.value
                ?: inferUnmarkedTitleType(haystack)
        }

        /** The Umbraco grid class wrapping exactly one event. */
        private const val EVENT_BOX_CLASS = "box-rc-dark-grey"

        /** Bootstrap column positions inside an event box: date + flyer, the event itself, the ticket panel. */
        private const val DATE_COLUMN = 0
        private const val MAIN_COLUMN = 1
        private const val TICKET_COLUMN = 2

        /** The four-digit year in a `AUGUST 2026` month heading; the row under it carries its own day and month. */
        private val HEADING_YEAR = Regex("""\b(20\d{2})\b""")

        /** How much of a spelled-out `SEPTEMBER` heading [parseGermanMonthAbbreviation] needs. */
        private const val GERMAN_MONTH_PREFIX = 3

        /** The `DO | 20.08.` row date, capturing day (group 1) and month (group 2); the weekday is redundant. */
        private val DAY_MONTH = Regex("""(\d{1,2})\.(\d{1,2})\.""")

        /** A label that identifies the info paragraph among the box's paragraphs. */
        private val INFO_LABEL = Regex("""Einlass|Beginn|Tickets""", RegexOption.IGNORE_CASE)

        /** `Beginn & Einlass: 18:00 Uhr` in either word order — one time serving as both. */
        private val COMBINED_TIME = Regex("""(?:Beginn|Einlass)\s*&\s*(?:Einlass|Beginn)\s*:\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

        private val DOORS_TIME = Regex("""Einlass\s*:\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)
        private val START_TIME = Regex("""Beginn\s*:\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

        /**
         * The promoter behind a `Präsentiert von: Loft Concerts` credit, captured up to the next
         * `Tickets:` label or the end of the line — the venue writes the credit before or after the
         * ticket line depending on the event.
         */
        private val PROMOTER = Regex("""Präsentiert von\s*:\s*(.+?)(?:\s*Tickets\s*:|$)""", RegexOption.IGNORE_CASE)

        /** A monetary amount in either notation, written with a `€` sign or the word `Euro`. */
        private val EURO_AMOUNT = Regex("""(\d+(?:[.,]\d{1,2})?)\s*(?:€|Euro)""", RegexOption.IGNORE_CASE)

        /**
         * The box-office price behind an `Abendkasse`/`Tageskasse` label, tolerating the venue's
         * optional colon and its `Ab` (from) qualifier. Deliberately requires digits, so the frequent
         * `Abendkasse TBA` yields no price at all rather than a wrong one.
         */
        private val BOX_OFFICE_PRICE =
            Regex("""(?:Abendkasse|Tageskasse)\s*:?\s*(?:Ab\s+)?(\d+(?:[.,]\d{1,2})?\s*(?:€|Euro))""", RegexOption.IGNORE_CASE)

        /** The ticket column's trailing call to action, dropped from the price note. */
        private val CALL_TO_ACTION = Regex("""\s*Für mehr Infos\s*hier klicken\s*:?\s*(?:Details)?\s*$""", RegexOption.IGNORE_CASE)

        /** The label a real ticket statement opens with, as opposed to the cancelled column's notice. */
        private const val TICKETS_LABEL = "Tickets"

        /** The `[Abgesagt!]` marker the venue appends to a cancelled event's title, stripped from the stored title. */
        private val CANCELLED_TITLE_MARKER = Regex("""\s*\[\s*Abgesagt!?\s*]""", RegexOption.IGNORE_CASE)

        /** The German cancellation word, in the title marker and in the ticket column's "Leider abgesagt!". */
        private const val CANCELLED_MARKER = "abgesagt"

        /** The German sold-out word the ticket column shows instead of a price. */
        private const val SOLD_OUT_MARKER = "ausverkauft"

        /**
         * Formats this house names in its own words, which the shared classifier does not cover: its
         * children's theatre and talk show are staged shows, its silent-film nights are screenings,
         * and its tea dance and after-work night are parties. Checked before
         * [inferUnmarkedTitleType], lowercase, first match wins.
         */
        private val VENUE_FORMAT_KEYWORDS: Map<String, String> =
            linkedMapOf(
                "kindertheater" to EventType.SHOW.name,
                "talkshow" to EventType.SHOW.name,
                "stummfilm" to EventType.SCREENING.name,
                "tanztee" to EventType.PARTY.name,
                "tanzcafé" to EventType.PARTY.name,
                "after work" to EventType.PARTY.name
            )
    }
}
