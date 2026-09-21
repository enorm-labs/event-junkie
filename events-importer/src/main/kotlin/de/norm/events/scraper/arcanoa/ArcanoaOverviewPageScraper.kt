package de.norm.events.scraper.arcanoa

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.arcanoa.ArcanoaOverviewPageScraper.Companion.RECURRING_FORMAT_PATTERN
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Pure HTML parser for Arcanoa Berlin's 1990s `veranst.htm` programme page.
 *
 * Nested `<font>`/`<table>` soup with no classes, ids or per-event URLs, so nothing per-event
 * to select. What *is* stable is the month rhythm: a `font.gesperrt` heading naming the month
 * ("Juli"), then one `<p>` holding that month's entire programme as `<br>`-separated lines:
 *
 * ```
 * Live Musik:
 * Mi 22.07.Live: Mittelalter-Irish Folk - freie Bühne -SpielleuteSession
 * Do 23.07.Live: Lobitos - AfroLatinFolkJazzEthnoBluesSession
 * ```
 *
 * The parser selects the month heading, takes the "Live Musik" paragraph from the surrounding
 * `<td>`, and splits its **flat text** on the `Mo 22.07.Live:` entry marker — an entry runs
 * from one marker to the next. Anchoring on the date marker rather than `<br>` positions makes
 * multi-line entries (a wrapped style tail) fall out for free.
 *
 * Two other blocks in the same month cell are skipped by that scoping: the undated
 * weekly-programme boxes above the listing (the dated listing already carries every occurrence,
 * so expanding them per ADR-007's "Undated Recurring Programmes" rule would only duplicate) and
 * the "Mittelaltertreffen immer Mittwoch" recap below it, repeating Wednesdays already listed.
 *
 * Dates carry a German weekday but no year, so the year comes from the weekday via
 * [inferYearForWeekday]. Passed events stay on the page; they are dropped centrally at
 * persistence (`EventUpsertService`), so this parser returns every dated entry as-is.
 *
 * @see ArcanoaWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.ssi-media.com/arcanoa/veranst.htm">Arcanoa programme</a>
 */
@Suppress("LongComment", "TooManyFunctions") // 5 KDoc lines are the 1990s markup itself, which needs many small extractors to parse.
class ArcanoaOverviewPageScraper(
    /** Clock for weekday-based year inference; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the programme page, one per dated line.
     *
     * @param baseUrl the URL the document was fetched from; every event's `sourceUrl`, since the
     * site has no per-event pages.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val monthBlocks = document.select("font.gesperrt").filter { it.text().trim() in GERMAN_MONTHS }
        logger.info { "Found ${monthBlocks.size} month block(s) on Arcanoa programme" }

        return monthBlocks.flatMap { heading -> parseMonthBlock(heading, baseUrl) }
    }

    /** Parses every dated entry in one month's "Live Musik" paragraph. */
    private fun parseMonthBlock(
        heading: Element,
        baseUrl: String
    ): List<ScrapedEvent> {
        val programme =
            heading.closest("td")?.select("p")?.firstOrNull { PROGRAMME_HEADING_PATTERN.containsMatchIn(it.text()) }
        if (programme == null) {
            logger.warn { "Month block '${heading.text()}' has no 'Live Musik' paragraph, skipping" }
            return emptyList()
        }

        // The page states one start time per month ("Veranstaltungsbeginn: 20 Uhr"); the only time it
        // publishes, sitting outside the programme paragraph.
        val startTime = parseStartTime(programme)

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed entries without aborting the import
        return splitIntoEntries(normalizeText(programme.text())).mapNotNull { entry ->
            try {
                parseEntry(entry, startTime, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Arcanoa entry '${entry.body}', skipping" }
                null
            }
        }
    }

    /**
     * Splits a month paragraph's flat text into entries at each `Mo 22.07.Live:` marker. A body
     * runs from the end of its own marker to the start of the next, so a wrapped style tail stays
     * with its entry. Text before the first marker (the "Live Musik:" caption) is dropped.
     */
    private fun splitIntoEntries(text: String): List<ProgrammeEntry> {
        val markers = ENTRY_PATTERN.findAll(text).toList()
        return markers.mapIndexed { index, marker ->
            val bodyEnd = markers.getOrNull(index + 1)?.range?.first ?: text.length
            val (weekday, day, month) = marker.destructured
            ProgrammeEntry(
                weekday = parseGermanWeekdayAbbreviation(weekday),
                day = day.toInt(),
                month = month.toInt(),
                body = text.substring(marker.range.last + 1, bodyEnd).trim()
            )
        }
    }

    /** Maps one programme entry onto a [ScrapedEvent], or `null` without an importable event. */
    @Suppress("ReturnCount") // Guard clauses for the unparseable/private-function cases are clearer than nesting
    private fun parseEntry(
        entry: ProgrammeEntry,
        startTime: LocalTime?,
        baseUrl: String
    ): ScrapedEvent? {
        val eventDate =
            entry.toEventDate() ?: run {
                logger.warn { "Could not parse date '${entry.day}.${entry.month}.' for '${entry.body}', skipping" }
                return null
            }

        // A "geschlossene Gesellschaft" is a private booking, not a public event — listed only to mark
        // the night as taken. Tested on the whole body: the marker also arrives as
        // `-- geschlossene Gesellschaft --` and behind a name (#1553).
        if (PRIVATE_FUNCTION_PATTERN.containsMatchIn(entry.body)) {
            logger.debug { "Arcanoa night on $eventDate is a private function ('${entry.body}'), skipping" }
            return null
        }

        val (rawTitle, rawSubtitle) = splitTitleAndSubtitle(entry.body)
        val title = normalizeDashSpacing(rawTitle)
        if (title.isBlank()) {
            logger.warn { "Arcanoa entry on $eventDate has no title, skipping" }
            return null
        }

        val eventType = inferConcertVenueType(title)
        val subtitle = rawSubtitle?.let { normalizeDashSpacing(it.trimStart('+', '-', ' ')) }?.takeIf { it.isNotBlank() }

        return ScrapedEvent(
            title = title,
            // The style tail ("HellCountryBlues", "Rock mit Sounds von Jazz u. Funk") is display prose,
            // not a normalizable genre — kept as a subtitle so it never seeds junk tags.
            subtitle = subtitle,
            eventType = eventType,
            eventDate = eventDate,
            startTime = startTime,
            // No per-event pages on this single-page site — the programme page is the source.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.ARCANOA.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            artists = parseArtists(title, subtitle, eventType)
        )
    }

    /**
     * Splits an entry body into title and the style/description tail.
     *
     * The venue writes `"<act> - <style>"` with inconsistent spacing around the dash, so the
     * separator is picked in two tiers: a fully spaced `" - "` first, else a dash with whitespace
     * on one side (`"Klonn -dadaistische KlangWelten"`). The tiers matter — the first half-spaced
     * dash would cut `"ARCANOA- Open Stage - SingerSongwriter"` after "ARCANOA". A dash with no
     * whitespace is never a separator, keeping "Mittelalter-Irish Folk" whole. A colon wins over
     * the dash when it comes first, so a labelled line ("JAM für Alle: 19-21 Uhr: Songwriting
     * workshop …") is titled by its label, not the whole blurb.
     */
    private fun splitTitleAndSubtitle(body: String): Pair<String, String?> {
        val dash = SPACED_DASH_PATTERN.find(body) ?: HALF_SPACED_DASH_PATTERN.find(body)
        val colon = body.indexOf(':').takeIf { it >= 0 && (dash == null || it < dash.range.first) }
        val separator = colon?.let { it..it } ?: dash?.range ?: return body.trim() to null
        return body.substring(0, separator.first).trim() to body.substring(separator.last + 1).trim().ifBlank { null }
    }

    /**
     * The headline act(s) named by the [title], or none for the venue's recurring formats. The
     * title is the only artist signal, and for a live-music venue usually right ("Mojo Substrat",
     * "Jesse Cotton Stone"). But roughly half the nights are standing formats whose "act" is the
     * format — the Monday open stage, the Wednesday medieval session, the Tuesday jam — which must
     * not be minted as artists, so [RECURRING_FORMAT_PATTERN] drops them.
     */
    private fun parseArtists(
        title: String,
        subtitle: String?,
        eventType: String
    ): List<ScrapedArtist> =
        buildArtistsForEventType(title, subtitle, eventType)
            .filterNot { RECURRING_FORMAT_PATTERN.containsMatchIn(it.name) }

    /**
     * The month block's shared "Veranstaltungsbeginn: 20 Uhr" start time, or `null`. The line sits
     * outside the [programme] paragraph but inside the same month cell, so the search widens to that cell.
     */
    private fun parseStartTime(programme: Element): LocalTime? {
        val match = START_TIME_PATTERN.find((programme.closest("td") ?: programme).text()) ?: return null
        val (hour, minute) = match.destructured
        return runCatching { LocalTime.of(hour.toInt(), minute.toIntOrNull() ?: 0) }.getOrNull()
    }

    /** One dated line of a month's programme, before its body is mapped onto event fields. */
    private inner class ProgrammeEntry(
        val weekday: DayOfWeek?,
        val day: Int,
        val month: Int,
        val body: String
    ) {
        /** The entry's date, year inferred from its weekday; `null` when day/month are invalid. */
        fun toEventDate(): LocalDate? {
            val monthDay =
                try {
                    MonthDay.of(month, day)
                } catch (_: DateTimeException) {
                    return null
                }
            return inferYearForWeekday(monthDay, weekday, clock)
        }
    }

    companion object {
        /** Collapses runs of whitespace, including the non-breaking spaces the page uses for indentation. */
        private val WHITESPACE_RUN = Regex("""[\s ]+""")

        private fun normalizeText(text: String): String = text.replace(WHITESPACE_RUN, " ").trim()

        /**
         * Pads a dash with whitespace on at least one side out to `" - "`, so `"Klonn -dadaistische"`
         * and `"ARCANOA- Open Stage"` read the same. A dash with no surrounding whitespace is part of
         * a name ("Mittelalter-Irish Folk") and untouched.
         */
        private val UNEVEN_DASH = Regex("""\s+-\s*|\s*-\s+""")

        private fun normalizeDashSpacing(text: String): String = text.replace(UNEVEN_DASH, " - ").trim()

        /** German month names, as rendered in the `font.gesperrt` block headings. */
        private val GERMAN_MONTHS: Set<String> =
            setOf(
                "Januar",
                "Februar",
                "März",
                "April",
                "Mai",
                "Juni",
                "Juli",
                "August",
                "September",
                "Oktober",
                "November",
                "Dezember"
            )

        /** The caption identifying a month block's programme paragraph ("Live Musik:"). */
        private val PROGRAMME_HEADING_PATTERN = Regex("""Live\s*Musik""", RegexOption.IGNORE_CASE)

        /**
         * Opens a programme entry: a German weekday abbreviation, a `DD.MM.` date, and the venue's
         * redundant "Live:" label — `"Mi 22.07.Live: "`. The label is optional; the venue omits it on
         * the occasional line.
         */
        private val ENTRY_PATTERN =
            Regex("""\b(Mo|Di|Mi|Do|Fr|Sa|So)\s+(\d{1,2})\.(\d{1,2})\.\s*(?:Live\s*:)?\s*""")

        /** The month block's shared start time, e.g. "Veranstaltungsbeginn: 20 Uhr" or "20.30 Uhr". */
        private val START_TIME_PATTERN =
            Regex("""Veranstaltungsbeginn:\s*(\d{1,2})(?:[.:](\d{2}))?\s*Uhr""", RegexOption.IGNORE_CASE)

        /** A dash with whitespace on both sides — the cleanest title/style separator. */
        private val SPACED_DASH_PATTERN = Regex("""\s+-\s+""")

        /** A dash with whitespace on exactly one side — the fallback separator. */
        private val HALF_SPACED_DASH_PATTERN = Regex("""\s-|-\s""")

        /** A night the venue is booked for a private party, not a public event. */
        private val PRIVATE_FUNCTION_PATTERN = Regex("""geschlossene\s+Gesellschaft""", RegexOption.IGNORE_CASE)

        /**
         * Arcanoa's standing weekly formats, programmes rather than performers: the Monday/Tuesday
         * open stage and jam, the Wednesday `SpielleuteSession` medieval night, the Liedermacher
         * festival, and the venue's own name leading its house nights. Venue-local rather than in the
         * shared `ArtistNameMapping` denylist — every entry is specific to this programme. Matched as a
         * substring on an already-split act name, so a co-billed real act survives. `jam session`
         * beside `\bjam\b` is not redundant: the venue also writes "JamSession" run-together, where
         * the trailing boundary `\bjam\b` needs is absent — and a bare `\bjam` prefix would swallow
         * "Jamiroquai".
         */
        private val RECURRING_FORMAT_PATTERN =
            Regex(
                """\barcanoa\b|open\s*stage|\bjam[\s-]*session\b|\bjam\b|spielleute|mittelalter""" +
                    """|liedermacherfestival|singersongwriter""",
                RegexOption.IGNORE_CASE
            )
    }
}
