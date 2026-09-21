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
 * The whole programme is one `<table>` on `?p=programm` (byte-identical to the homepage). Every
 * night is a `<tr class="event" id="YYYYMMDDHHMM">` whose **`id` is a date+time stamp** — the
 * most stable signal on the page (ADR-007 selector priority 4) and the basis for
 * [ScrapedEvent.eventDate] and a stable [ScrapedEvent.sourceId]. Its `td.date` carries a
 * year-less `DD.MM.`, an `HH:MM` time and an optional flyer; its `td.evcont` holds one
 * `div.even` per billed act, each a `.tit` name, an optional `.beschr` note and a `.lin` link.
 *
 * - **The lineup is the billing.** No separate headline: the venue's own RSS renders a night as
 * its act names joined with ", ", so that joined string is the title and the `.even` names the
 * artist list, first billed the headliner. A block with an empty `.tit` is an extra reference
 * link for the act above, not an act, and dropped.
 * - **Monthly programme posters are rows.** A flyer-only row titled "September Programm 2026"
 * ([PROGRAMME_POSTER_TITLE]) announces the printed programme, not an event.
 * - **Service notes sit in an act slot.** The weekly "Kuchen & Kaffee 15:30 Uhr" social is
 * billed like an act; a name carrying an inline `HH:MM Uhr` ([isScheduleNote]) is a programme
 * note, so it stays the title but is never an artist — which types the night via
 * [inferUnmarkedTitleType] instead of defaulting to a concert.
 *
 * @see SUPAMOLLY_LIMITATIONS for what the venue does not publish.
 * @see SupamollyWebsiteImporter for the HTTP fetch orchestrator.
 */
class SupamollyOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the programme page.
     *
     * @param baseUrl the URL the document was fetched from, for resolving relative links.
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
     * Parses one `<tr class="event">` row into a [ScrapedEvent], or `null` without a
     * `YYYYMMDDHHMM` id or billed act name, or for a monthly programme poster.
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
     * One `div.even` block as act name and note. Both are read off a clone with the block's two
     * non-content children removed — `.progln` (a spacer carrying the reference URL in an `alt`)
     * and `.lin` (the visible copy) — so neither leaks into name or description. Reading `.tit`
     * rather than `.tit b` survives the HTML5 parser restructuring the `<b>` wrapping a nested `<div>`.
     */
    private fun parseActBlock(block: Element): ActBlock {
        val work = block.clone()
        work.select(".progln, .lin").remove()
        return ActBlock(name = work.textAt(".tit"), description = work.textAt(".beschr"))
    }

    /**
     * The billed act names as artist entries, in billing order. Each is stripped of a leading
     * co-bill conjunction (`"& Support"` → `"Support"`, so the shared [isNonArtistName] recognises
     * the bare role label) and a trailing tour/live/format tail ([stripArtistSuffix]), then dropped
     * if not a performer ([isNonArtistName]) or a programme note ([isScheduleNote]). First survivor
     * is headliner, the rest support.
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
     * The flyer URL from the row's thumbnail. The markup links a `flyer/small/<stamp>.jpg`
     * thumbnail (~3 KB); the full-size poster is `flyer/<stamp>.jpg` — the image the row's
     * `index.php?programm=<stamp>` link serves — so the `small/` segment is dropped. A path without
     * that segment is kept as-is; a row without a flyer degrades to `null`.
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

    /** The `YYYYMMDD` date from a row's stamp id, or `null` when not a real calendar date. */
    private fun parseStampDate(stamp: String): LocalDate? =
        try {
            LocalDate.parse(stamp.take(DATE_LENGTH), STAMP_DATE_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }

    /** The trailing `HHMM` time from a row's stamp id, or `null` when not a real time. */
    private fun parseStampTime(stamp: String): LocalTime? =
        try {
            LocalTime.parse(stamp.drop(DATE_LENGTH), STAMP_TIME_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }

    /**
     * True when an act name is a programme note — an inline `HH:MM Uhr` schedule (`"Kuchen &
     * Kaffee 15:30 Uhr"`, the weekly café social). A performer never states their own start time,
     * so this keeps service listings out of the artist table while leaving them as the title.
     */
    private fun isScheduleNote(name: String): Boolean = SCHEDULE_NOTE_PATTERN.containsMatchIn(name)

    /** One billed `div.even` block: the act name and its note, either of which may be absent. */
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
         * A flyer-only row announcing the month's printed programme ("September Programm 2026",
         * "September Prog 2026"). Anchored to the whole title and keyed on a German month name followed
         * by `Prog`/`Programm`, so a real event merely mentioning a month is untouched.
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
