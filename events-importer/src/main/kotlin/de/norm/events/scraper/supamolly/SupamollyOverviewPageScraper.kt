package de.norm.events.scraper.supamolly

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.supamolly.SupamollyOverviewPageScraper.Companion.PROGRAMME_POSTER_TITLE
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure HTML parser for Supamolly Berlin's retro hand-coded single-page programme.
 *
 * The whole programme is one `<table>` on `?p=programm` (byte-identical to the
 * homepage). Every night is a `<tr class="event" id="YYYYMMDDHHMM">` row whose
 * **`id` is a date+time stamp** — the most stable signal on the page (ADR-007
 * selector priority 4, a `data`-style identifier) and the basis for both
 * [ScrapedEvent.eventDate] and a stable [ScrapedEvent.sourceId]. Each row pairs:
 * - `td.date` — weekday icon, a year-less `DD.MM.` date, an `HH:MM` time
 *   (`.uhr`), and an optional flyer thumbnail; and
 * - `td.evcont` — one `div.even` block per **billed act**, each with a `.tit`
 *   name, an optional `.beschr` note, and a `.lin` artist reference link
 *   (Bandcamp / YouTube / Instagram).
 *
 * Three quirks drive the design:
 * - **The lineup is the billing.** There is no separate headline: the venue's own
 *   RSS renders a night as its act names joined with ", ", so that joined string is
 *   the event title, and the individual `.even` names become the artist list (first
 *   billed = headliner). Blocks with an empty `.tit` are extra reference links for
 *   the act above, not acts, and are dropped.
 * - **Monthly programme posters are listed as rows.** A flyer-only row titled
 *   "September Programm 2026" ([PROGRAMME_POSTER_TITLE]) announces the month's
 *   printed programme rather than an event, and is skipped.
 * - **Service notes sit in an act slot.** The weekly "Kuchen & Kaffee 15:30 Uhr"
 *   social is billed like an act; a name carrying an inline `HH:MM Uhr`
 *   ([isScheduleNote]) is a programme note, so it stays the event title but is never
 *   minted as an artist — which in turn types the night via [inferUnmarkedTitleType]
 *   instead of defaulting it to a concert.
 *
 * The venue publishes no prices and no ticket shop, so those fields stay null.
 *
 * @see SupamollyWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.supamolly.de/?p=programm">Supamolly Berlin</a>
 */
class SupamollyOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the Supamolly programme page document.
     *
     * @param baseUrl the URL the document was fetched from, used for resolving relative links.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("tr.event[id]")
        logger.info { "Found ${rows.size} event row(s) on the Supamolly programme page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed rows without aborting the import
        return rows.mapNotNull { row ->
            try {
                parseEventRow(row, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Supamolly event row, skipping" }
                null
            }
        }
    }

    /**
     * Parses a single `<tr class="event">` row into a [ScrapedEvent].
     *
     * Skips (returns `null`) when the row carries no `YYYYMMDDHHMM` id, no billed
     * act name, or is a monthly programme poster — so neither malformed rows nor
     * non-events reach persistence.
     */
    @Suppress("ReturnCount") // Guard clauses for the required id/title fields and the poster row are clearer than nesting
    private fun parseEventRow(
        row: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val stamp = row.id().takeIf { STAMP_PATTERN.matches(it) }
        if (stamp == null) {
            logger.warn { "Supamolly event row has no YYYYMMDDHHMM id (got '${row.id()}'), skipping" }
            return null
        }

        val eventDate = parseStampDate(stamp)
        if (eventDate == null) {
            logger.warn { "Could not parse date from Supamolly row id '$stamp', skipping" }
            return null
        }

        val blocks = row.select("td.evcont .even").map { parseActBlock(it) }
        val actNames = blocks.mapNotNull { it.name }
        val rawTitle = actNames.joinToString(", ")
        if (rawTitle.isBlank()) {
            logger.warn { "Supamolly row '$stamp' bills no act, skipping" }
            return null
        }
        if (PROGRAMME_POSTER_TITLE.matches(rawTitle)) {
            logger.info { "Skipping Supamolly monthly programme poster row '$stamp' ($rawTitle)" }
            return null
        }

        val title = cleanEventTitle(rawTitle)
        val artists = buildArtists(actNames)

        return ScrapedEvent(
            title = title,
            description = blocks.mapNotNull { it.description }.joinToString("\n").takeIf { it.isNotBlank() },
            // Artist-less nights are the venue's socials and film/quiz evenings, not gigs — see class KDoc.
            eventType = if (artists.isEmpty()) inferUnmarkedTitleType(title) else inferConcertVenueType(title),
            eventDate = eventDate,
            // The `.uhr` cell is the displayed time; the id's trailing HHMM is the same value, used as fallback.
            startTime = parseTime(row.textAt("td.date .uhr")) ?: parseStampTime(stamp),
            imageUrl = parseImageUrl(row, baseUrl),
            sourceUrl = resolveUrl(baseUrl, "#$stamp"),
            sourceId = "${EventSource.SUPAMOLLY.sourceIdPrefix}$stamp",
            status = parseEventStatus(rawTitle),
            artists = artists
        )
    }

    /**
     * Reads one `div.even` block into its act name and note.
     *
     * Both fields are read off a clone with the block's two non-content children
     * removed — `.progln` (a spacer carrying the reference URL in an `alt`) and
     * `.lin` (the visible copy of that URL) — so neither leaks into the name or the
     * description. Reading `.tit` rather than `.tit b` keeps this robust to the
     * HTML5 parser restructuring the `<b>` that wraps a nested `<div>`.
     */
    private fun parseActBlock(block: Element): ActBlock {
        val work = block.clone()
        work.select(".progln, .lin").remove()
        return ActBlock(name = work.textAt(".tit"), description = work.textAt(".beschr"))
    }

    /**
     * Turns the billed act names into artist entries, in billing order.
     *
     * Each name is stripped of a leading co-bill conjunction (`"& Support"` →
     * `"Support"`, so the shared [isNonArtistName] can recognise the bare role
     * label) and of a trailing tour/live/format tail ([stripArtistSuffix]), then
     * dropped if it is not a performer ([isNonArtistName]) or is a programme note
     * ([isScheduleNote]). The first survivor is billed as headliner, the rest as
     * support.
     */
    private fun buildArtists(actNames: List<String>): List<ScrapedArtist> =
        actNames
            .map { stripArtistSuffix(it.replaceFirst(LEADING_CONJUNCTION, "")) }
            .filter { it.isNotBlank() && !isNonArtistName(it) && !isScheduleNote(it) }
            .distinct()
            .mapIndexed { index, name ->
                ScrapedArtist(name = name, role = if (index == 0) "HEADLINER" else "SUPPORT")
            }

    /**
     * Resolves the flyer URL from the row's thumbnail.
     *
     * The markup links a `flyer/small/<stamp>.jpg` thumbnail (~3 KB); the full-size
     * poster lives at `flyer/<stamp>.jpg` — the same image the row's
     * `index.php?programm=<stamp>` link serves — so the `small/` path segment is
     * dropped to prefer the usable resolution. A thumbnail whose path does not carry
     * that segment is kept as-is, and a row without a flyer degrades to `null`.
     */
    private fun parseImageUrl(
        row: Element,
        baseUrl: String
    ): String? {
        val src =
            row
                .selectFirst(".flyer_small_frame img")
                ?.attr("src")
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { resolveUrl(baseUrl, src).replace(FLYER_THUMBNAIL_SEGMENT, "/flyer/") }.getOrNull()
    }

    /** Reads the `YYYYMMDD` date from a row's stamp id, or `null` when it is not a real calendar date. */
    private fun parseStampDate(stamp: String): LocalDate? =
        try {
            LocalDate.parse(stamp.take(DATE_LENGTH), STAMP_DATE_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }

    /** Reads the trailing `HHMM` time from a row's stamp id, or `null` when it is not a real time. */
    private fun parseStampTime(stamp: String): LocalTime? =
        try {
            LocalTime.parse(stamp.drop(DATE_LENGTH), STAMP_TIME_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }

    /**
     * True when an act name is really a programme note — it carries an inline
     * `HH:MM Uhr` schedule (e.g. `"Kuchen & Kaffee 15:30 Uhr"`, the venue's weekly
     * café social). A performer name never states its own start time, so this keeps
     * the venue's service listings out of the artist table while leaving them as the
     * event title.
     */
    private fun isScheduleNote(name: String): Boolean = SCHEDULE_NOTE_PATTERN.containsMatchIn(name)

    /** One billed `div.even` block: the act name and its accompanying note, either of which may be absent. */
    private data class ActBlock(
        val name: String?,
        val description: String?
    )

    companion object {
        /** A row id is a `YYYYMMDDHHMM` stamp — 12 digits, nothing else. */
        private val STAMP_PATTERN = Regex("""\d{12}""")

        /** Digits of the stamp that carry the date; the remainder is the `HHmm` time. */
        private const val DATE_LENGTH = 8

        private val STAMP_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        private val STAMP_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

        /**
         * A flyer-only row announcing the month's printed programme ("September Programm
         * 2026", "September Prog 2026") rather than an event. Anchored to the whole title
         * and keyed on a German month name followed by `Prog`/`Programm`, so a real event
         * merely mentioning a month is untouched.
         */
        private val PROGRAMME_POSTER_TITLE =
            Regex(
                """(?:januar|februar|m(?:ä|ae)rz|april|mai|juni|juli|august|september|oktober|november|dezember)""" +
                    """\s+prog(?:ramm)?\.?(?:\s+\d{4})?""",
                RegexOption.IGNORE_CASE
            )

        /** A leading `&` / `+` co-bill conjunction on an act name ("& Support" → "Support"). */
        private val LEADING_CONJUNCTION = Regex("""^\s*[&+]\s*""")

        /** An inline `HH:MM Uhr` schedule in an act name — the signature of a programme note, not a performer. */
        private val SCHEDULE_NOTE_PATTERN = Regex("""\d{1,2}[:.]\d{2}\s*uhr""", RegexOption.IGNORE_CASE)

        /** The thumbnail path segment dropped to reach the full-size flyer (`flyer/small/x.jpg` → `flyer/x.jpg`). */
        private const val FLYER_THUMBNAIL_SEGMENT = "/flyer/small/"
    }
}
