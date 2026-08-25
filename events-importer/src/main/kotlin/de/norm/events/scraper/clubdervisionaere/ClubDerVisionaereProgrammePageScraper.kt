package de.norm.events.scraper.clubdervisionaere

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.clubdervisionaere.ClubDerVisionaereProgrammePageScraper.Companion.FLOOR_LABEL_PATTERN
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/**
 * Pure HTML parser for the Club der Visionäre programme page, shared by all three
 * rooms it lists (see [ClubDerVisionaereRoom]).
 *
 * The page is one chronological run of `div#programmC > div[id^=post-]` blocks, each carrying a
 * `div.headerTxt` date cell, a `p.headerTxt.<room>` title whose colour class names the room, and a
 * flat sequence of `<p>` lineup lines. [scrape] walks **every** block — the date carry-forward below
 * spans rooms — but returns only those belonging to the requested room.
 *
 * Three quirks drive the parsing:
 *
 * 1. **Year-less dates.** The date cell reads `Fr. 31.7.`, sometimes without the dot after the
 *    weekday. The German weekday disambiguates the year via [inferYearForWeekday].
 * 2. **Dates are printed once per day.** When a night shares its date with the block
 *    above it — a boat party and the club afterparty that follows it — the second
 *    block's date cell is *empty*, so a dateless block inherits the preceding block's
 *    date. A dateless block with nothing before it is skipped rather than guessed at.
 * 3. **The lineup is prose, not markup.** Acts are `// <name>` paragraphs, optionally
 *    grouped under a `<label>:` paragraph — a floor (`Main:`, `Chill Floor:`) or a
 *    billing section (`Live Band featuring:`, `DJ Sets:`). A trailing `LIVE` marker or
 *    a `from HH:mm` set time may ride along on the act line; the set time is dropped
 *    rather than used as the event's start time, which the venue never publishes.
 *
 * Parenthesised names are deliberately left whole — `Los Refrescos (Dandy Jack &
 * Argenis Brito)` is one act billed with its members, and `Naima (2)` is a Resident
 * Advisor disambiguator — so the conjunction split is skipped for them.
 *
 * @see CLUB_DER_VISIONAERE_LIMITATIONS for what the venue does not publish.
 * @see ClubDerVisionaereWebsiteImporter and its sibling room importers for the fetch orchestration.
 */
class ClubDerVisionaereProgrammePageScraper(
    /** Clock for weekday-based year inference. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the programme page, returning only the events of the given [room].
     *
     * @param sourceUrl the URL the document was fetched from; the page has no per-event
     *   URLs, so it doubles as every event's `sourceUrl`.
     * @param room the room whose nights to return.
     */
    fun scrape(
        document: Document,
        sourceUrl: String,
        room: ClubDerVisionaereRoom
    ): List<ScrapedEvent> {
        val blocks = document.select(EVENT_BLOCK_SELECTOR)
        logger.info { "Found ${blocks.size} programme block(s) on the Club der Visionäre page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed blocks without aborting the import.
        val events =
            withCarriedDates(blocks)
                .filter { (block, _) -> roomOf(block) == room }
                .mapNotNull { (block, date) ->
                    try {
                        parseBlock(block, date, sourceUrl, room)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to parse Club der Visionäre block ${block.id()}, skipping" }
                        null
                    }
                }

        logger.info { "Parsed ${events.size} event(s) for ${room.eventSource.name}" }
        return events
    }

    /**
     * Pairs each block with its date, carrying the last seen date forward into blocks
     * whose date cell is empty (quirk 2 in the class doc). Runs over all rooms' blocks
     * because a night can inherit its date from a block belonging to another room.
     */
    private fun withCarriedDates(blocks: List<Element>): List<Pair<Element, LocalDate?>> {
        var lastDate: LocalDate? = null
        return blocks.map { block ->
            lastDate = parseDate(block) ?: lastDate
            block to lastDate
        }
    }

    /** The room a block belongs to, read from its title's colour class; null when the title is missing or unknown. */
    private fun roomOf(block: Element): ClubDerVisionaereRoom? {
        val title = block.selectFirst(TITLE_SELECTOR) ?: return null
        return ClubDerVisionaereRoom.entries.firstOrNull { title.hasClass(it.titleClass) }
    }

    @Suppress("ReturnCount") // Guard clauses for the required title/date/id are clearer than nesting.
    private fun parseBlock(
        block: Element,
        eventDate: LocalDate?,
        sourceUrl: String,
        room: ClubDerVisionaereRoom
    ): ScrapedEvent? {
        val title =
            block.textAt(TITLE_SELECTOR)?.let(::cleanEventTitle) ?: run {
                logger.warn { "Club der Visionäre block ${block.id()} has no title, skipping" }
                return null
            }

        val postId = block.id().removePrefix(POST_ID_PREFIX).takeIf { it.isNotBlank() && it != block.id() }
        if (postId == null) {
            logger.warn { "Club der Visionäre block for '$title' has no post id, skipping" }
            return null
        }

        if (eventDate == null) {
            logger.warn { "No date available for Club der Visionäre event '$title' (no preceding dated block), skipping" }
            return null
        }

        return ScrapedEvent(
            title = title,
            // Every listing is a club night; the venue publishes no category of its own.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            // No per-event pages: the programme page is the source for every night.
            sourceUrl = sourceUrl,
            sourceId = "${room.eventSource.sourceIdPrefix}$postId",
            artists = parseLineup(block)
        )
    }

    /**
     * Parses the block's German `Wd. D.M.` date cell, inferring the year from the
     * weekday. Returns `null` for an empty cell (the shared-date case) or an
     * unparseable one.
     */
    @Suppress("ReturnCount") // Null-safe early exits per date component are clearer than nested let-chains.
    private fun parseDate(block: Element): LocalDate? {
        val dateText = block.textAt(DATE_SELECTOR) ?: return null
        val match = DATE_PATTERN.find(dateText) ?: return null
        val (weekdayText, day, month) = match.destructured
        val monthDay =
            try {
                MonthDay.of(month.toInt(), day.toInt())
            } catch (_: DateTimeException) {
                return null
            }
        return inferYearForWeekday(monthDay, GERMAN_WEEKDAY_ABBREVIATIONS[weekdayText.lowercase()], clock)
    }

    /**
     * Reads the block's `// <act>` lineup lines into artists, tracking the floor and
     * billing section they are listed under.
     *
     * A `<label>:` paragraph opens a section: a floor label ([FLOOR_LABEL_PATTERN])
     * becomes the [stage][ScrapedArtist.stage] of the acts below it, while a live-band
     * section bills them as headliners. Anything else resets both, so a section that
     * names neither cannot leak a bogus stage onto later acts.
     *
     * The result is de-duplicated by name: an act billed twice on one night (a live-band
     * member who also plays a DJ set later, e.g. Remain In Love) would otherwise produce
     * two `event_artist` rows for the same pair and violate its unique constraint. The
     * first billing wins, keeping both the earlier lineup position and its role.
     */
    private fun parseLineup(block: Element): List<ScrapedArtist> {
        var stage: String? = null
        var sectionIsLive = false
        val artists = mutableListOf<ScrapedArtist>()

        for (paragraph in block.select(LINEUP_LINE_SELECTOR)) {
            val line = paragraph.text().replace(NON_BREAKING_SPACE, ' ').trim()
            when {
                line.isBlank() -> {
                    continue
                }

                !line.startsWith(ACT_MARKER) -> {
                    val label = line.removeSuffix(":").trim()
                    stage = label.takeIf { FLOOR_LABEL_PATTERN.containsMatchIn(it) }
                    sectionIsLive = LIVE_MARKER_PATTERN.containsMatchIn(label)
                }

                else -> {
                    artists += parseActLine(line, stage, sectionIsLive)
                }
            }
        }
        return artists.distinctBy { SlugGenerator.slugify(it.name) }
    }

    /**
     * Turns one `// <act>` line into its artist entries — usually one, two for a co-bill
     * or a `b2b` slot.
     *
     * An act the venue marks `LIVE` (or one listed under a live-band section) is billed
     * [HEADLINER][de.norm.events.event.ArtistRole.HEADLINER] rather than
     * [DJ][de.norm.events.event.ArtistRole.DJ]: the marker is the venue's own statement
     * that the act performs rather than DJs, and HEADLINER is the only performing role the
     * model has. Everything else on this programme is a DJ set.
     */
    private fun parseActLine(
        line: String,
        stage: String?,
        sectionIsLive: Boolean
    ): List<ScrapedArtist> {
        val cleaned =
            line
                .removePrefix(ACT_MARKER)
                .replace(SET_TIME_TAIL_PATTERN, "")
                .replaceFirst(LINEUP_LABEL_PREFIX, "")
                .trim()
        val role = if (sectionIsLive || LIVE_MARKER_PATTERN.containsMatchIn(cleaned)) "HEADLINER" else "DJ"
        return splitActs(cleaned)
            .map(::stripArtistSuffix)
            .filterNot(::isUnannouncedAct)
            .map { ScrapedArtist(name = it, role = role, stage = stage) }
    }

    /**
     * Splits an act line into individual acts: always at a `b2b` marker, and at
     * `&`/`and`/`und` boundaries via [splitSegmentOnConjunctions] — but never inside a
     * parenthesised name, whose brackets hold a member list or a Resident Advisor
     * disambiguator rather than a second act.
     */
    private fun splitActs(line: String): List<String> =
        line
            .split(B2B_SEPARATOR)
            .flatMap { if (it.contains('(')) listOf(it) else splitSegmentOnConjunctions(it) }
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /**
     * True when [name] names no act yet: the shared non-artist predicate, plus the
     * venue's `More TBA` spelling — the `More ` prefix is only ever dropped when what
     * remains is itself a placeholder, so a real act like "More Ghost Than Man" is kept.
     */
    private fun isUnannouncedAct(name: String): Boolean = isNonArtistName(name) || isNonArtistName(name.replaceFirst(MORE_PREFIX, ""))

    companion object {
        /** Programme blocks: the WordPress post wrappers inside the programme column. */
        private const val EVENT_BLOCK_SELECTOR = "div#programmC > div[id^=post-]"

        /** The block's date cell — a `headerTxt` div, as opposed to the title's `headerTxt` paragraph. */
        private const val DATE_SELECTOR = "div.headerTxt"

        /** The block's title paragraph; its colour class names the room. */
        private const val TITLE_SELECTOR = "p.headerTxt"

        /** Lineup and section paragraphs: the content box's paragraphs other than the title. */
        private const val LINEUP_LINE_SELECTOR = "div.programmCContentBox > p:not(.headerTxt)"

        /** Prefix on the block's `id` attribute (`post-41724`). */
        private const val POST_ID_PREFIX = "post-"

        /** The venue's own bullet for a lineup entry. */
        private const val ACT_MARKER = "//"

        /** `&nbsp;` as Jsoup renders it — normalised so name splitting sees ordinary spaces. */
        private const val NON_BREAKING_SPACE = '\u00A0'

        /** German weekday abbreviation + `D.M.` date, with or without the dot after the weekday. */
        private val DATE_PATTERN = Regex("""([A-Za-zÄÖÜäöü]{2})\.?\s*(\d{1,2})\.(\d{1,2})\.""")

        /** A section heading naming a floor rather than a billing group — `Main`, `Chill Floor`. */
        private val FLOOR_LABEL_PATTERN = Regex("""\bfloor\b|^main$""", RegexOption.IGNORE_CASE)

        /** The venue's live-performance marker, on a section heading ("Live Band featuring") or an act ("… LIVE"). */
        private val LIVE_MARKER_PATTERN = Regex("""\blive\b""", RegexOption.IGNORE_CASE)

        /** A trailing set time on an act line ("… LIVE from 21:00") — a slot time, not the event's start. */
        private val SET_TIME_TAIL_PATTERN = Regex("""\s+from\s+\d{1,2}:\d{2}\s*$""", RegexOption.IGNORE_CASE)

        /** A leading series label on an act line ("Soundz of:  Guest DJs"), stripped so the act itself remains. */
        private val LINEUP_LABEL_PREFIX = Regex("""^[^:]{1,30}:\s+""")

        /** The `More` in the venue's "More TBA" not-yet-announced marker. */
        private val MORE_PREFIX = Regex("""^more\s+""", RegexOption.IGNORE_CASE)

        /** The back-to-back marker joining two DJs into one slot ("XDB b2b Onirik"). */
        private val B2B_SEPARATOR = Regex("""\s+b2b\s+""", RegexOption.IGNORE_CASE)

        /** German two-letter weekday abbreviations used in the date cells. */
        private val GERMAN_WEEKDAY_ABBREVIATIONS: Map<String, DayOfWeek> =
            mapOf(
                "mo" to DayOfWeek.MONDAY,
                "di" to DayOfWeek.TUESDAY,
                "mi" to DayOfWeek.WEDNESDAY,
                "do" to DayOfWeek.THURSDAY,
                "fr" to DayOfWeek.FRIDAY,
                "sa" to DayOfWeek.SATURDAY,
                "so" to DayOfWeek.SUNDAY
            )
    }
}
