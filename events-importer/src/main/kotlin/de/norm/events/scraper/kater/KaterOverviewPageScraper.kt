package de.norm.events.scraper.kater

import de.norm.events.event.EventType
import de.norm.events.scraper.B2B_SEPARATOR
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hostedActsFromTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.isScreeningTitle
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Pure HTML parser for Kater Berlin's homepage programme.
 *
 * The homepage is the whole source: the `event` REST route exposes no ACF fields (`acf` comes
 * back empty) and the `/event/<slug>` pages carry nothing but a heading. Each night is an
 * `article.event[id=event-<postId>]` with a `.date-title` name and an `.entry-summary` that
 * opens with a `Wd. DD.MM HH:mm — Wd. DD.MM HH:mm` span, then an optional Resident Advisor
 * ticket link, then free prose.
 *
 * **The prose is only sometimes a lineup, and the venue marks which.** A `____________` rule
 * introduces a floor (`HOPPER`, `ACID BOGEN`, `EXTRA`, sometimes suffixed `by <presenter>`)
 * and the lines beneath are that floor's DJs — what [ScrapedArtist.stage] is for. A summary
 * with no rule is a description — a garden evening, a film night, a residency's schedule notes
 * — and yields **no** artists, rather than minting "free entry till 20:00" or a film synopsis.
 *
 * Dates carry a weekday but no year, so the year comes from the weekday ([inferYearForWeekday]).
 *
 * @see KaterWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.katerclub.de/">Kater Berlin</a>
 */
class KaterOverviewPageScraper(
    /** Clock for the year inference, in the venue's time zone; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event articles from the homepage, in listing order.
     *
     * @param baseUrl the URL the document was fetched from, for each event's anchor URL.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        // `.resident` and `.awareness` articles share the page and the `hentry` markup; only the
        // `event` post type is a dated night.
        val articles = document.select("article.event")
        logger.info { "Found ${articles.size} event article(s) on Kater homepage" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed articles without aborting the whole import
        return articles.mapNotNull { article ->
            try {
                parseArticle(article, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse event article, skipping" }
                null
            }
        }
    }

    /** Parses one `article.event` into a [ScrapedEvent], or `null` without id, title or date. */
    @Suppress("ReturnCount") // Guard clauses for the required id / title / date are clearer than nesting
    private fun parseArticle(
        article: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val eventId = EVENT_ID_PATTERN.find(article.id())?.groupValues?.get(1) ?: return null
        val title = article.textAt(".date-title")?.let(::cleanEventTitle) ?: return null

        val lines = summaryLines(article)
        val schedule = lines.firstOrNull()?.let(::parseSchedule)
        if (schedule == null) {
            logger.warn { "No parseable date line for Kater event '$title' ($eventId), skipping" }
            return null
        }

        val body = lines.drop(1)
        val lineup = parseLineup(body)
        val description = describe(body).takeIf { it.isNotBlank() }

        return ScrapedEvent(
            title = title,
            description = description,
            // A techno club with no category field; only an unambiguous title keyword overrides the party default.
            eventType = if (isScreeningTitle(title)) EventType.SCREENING.name else EventType.PARTY.name,
            eventDate = schedule.date,
            startTime = schedule.startTime,
            endDate = schedule.endDate,
            endTime = schedule.endTime,
            // No per-event page worth fetching, so the homepage anchor is the URL.
            sourceUrl = "$baseUrl#$EVENT_ID_PREFIX$eventId",
            sourceId = "${EventSource.KATER.sourceIdPrefix}$eventId",
            ticketUrl = article.hrefAt("a.rsvp"),
            free = description?.let { FREE_ENTRY_PHRASE.containsMatchIn(it) } == true,
            // A night named for its curator and billed without a line-up (#339).
            artists = lineup.ifEmpty { hostedActsFromTitle(title, role = "DJ") }
        )
    }

    /**
     * The summary's text lines, with the ticket paragraph dropped. The summary mixes `<p>` blocks
     * and `<br>` breaks inconsistently — a floor rule and its name may sit in one paragraph while
     * the acts continue in the next — so every paragraph is flattened into one ordered stream first.
     */
    private fun summaryLines(article: Element): List<String> =
        article
            .select(".entry-summary p")
            .filter { it.selectFirst("a.rsvp") == null }
            .flatMap { paragraph -> paragraph.wholeText().split('\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /**
     * Splits the summary body into floors and their acts. Empty for a summary with no `____` rule:
     * the venue's own signal that the prose is a description, and reading it as acts would mint
     * schedule notes and film synopses as artists.
     */
    private fun parseLineup(body: List<String>): List<ScrapedArtist> {
        if (body.none { FLOOR_RULE.matches(it) }) return emptyList()

        val artists = mutableListOf<ScrapedArtist>()
        var stage: String? = null
        var expectFloorName = false
        for (line in body) {
            when {
                FLOOR_RULE.matches(line) -> {
                    expectFloorName = true
                }

                expectFloorName -> {
                    stage = line.replace(FLOOR_PRESENTER_SUFFIX, "").trim().takeIf { it.isNotBlank() }
                    expectFloorName = false
                }

                // Two kinds of line inside a floor block that bill nobody: `+ Darkroom` and
                // `+ Daytime Market`, what else the floor offers (#1564); and `Saturday by Muting
                // The Noise`, a second floor's own heading printed without the floor rule above it,
                // so the acts under it continue under the stage in hand (#1679).
                stage != null && !NOTE_LINE.containsMatchIn(line) && !WEEKDAY_HEADING.containsMatchIn(line) -> {
                    artists += splitActs(line).map { ScrapedArtist(name = it, role = "DJ", stage = stage) }
                }
            }
        }
        return artists.distinctBy { it.name.lowercase() to it.stage }
    }

    /** The lines that are not part of a floor block — the event's own blurb, if it has one. */
    private fun describe(body: List<String>): String {
        val floorStart = body.indexOfFirst { FLOOR_RULE.matches(it) }
        return (if (floorStart < 0) body else body.take(floorStart)).joinToString("\n")
    }

    /**
     * Splits an act line at a `b2b` marker and at safe `&`/`and`/`und` boundaries — but a
     * **parenthesised** line is never split, because the brackets hold a duo's member list rather
     * than a second slot ("Double Penetration (FLOWWW b2b Joe Cleen)" is one act). A trailing
     * `[LIVE]` marker is stripped, as is any tail [stripArtistSuffix] recognises.
     */
    private fun splitActs(line: String): List<String> =
        (if (line.contains('(')) listOf(line) else line.split(B2B_SEPARATOR).flatMap(::splitSegmentOnConjunctions))
            .map { stripArtistSuffix(it.replace(FORMAT_MARKER, "").trim()) }
            .filter { it.isNotBlank() && !isNonArtistName(it) }

    /** A night's span, once each half's year has been inferred. */
    private data class Schedule(
        val date: LocalDate,
        val startTime: LocalTime?,
        val endDate: LocalDate?,
        val endTime: LocalTime?
    )

    /**
     * Parses the summary's opening `Wd. DD.MM HH:mm — Wd. DD.MM HH:mm` line. Both halves are
     * read, the closing one usually the following morning (ADR-029). Neither carries a year, so
     * the weekday disambiguates each via [inferYearForWeekday]. A line without the closing half
     * still yields a start.
     */
    private fun parseSchedule(line: String): Schedule? {
        val groups = SCHEDULE_PATTERN.find(line)?.groupValues ?: return null
        val endDate = groups[END_DAY_GROUP].takeIf { it.isNotEmpty() }?.let { dateOf(it, groups[END_MONTH_GROUP], groups[END_WEEKDAY_GROUP]) }
        return dateOf(groups[DAY_GROUP], groups[MONTH_GROUP], groups[WEEKDAY_GROUP])?.let { date ->
            Schedule(
                date = date,
                startTime = parseTime(groups[TIME_GROUP]),
                endDate = endDate,
                // The column refuses a time without a date, so an unparseable closing date drops its time too.
                endTime = endDate?.let { parseTime(groups[END_TIME_GROUP]) }
            )
        }
    }

    /** A `DD.MM` with its weekday, or `null` when the pair is not a real date. */
    private fun dateOf(
        day: String,
        month: String,
        weekday: String
    ): LocalDate? =
        runCatching { MonthDay.of(month.toInt(), day.toInt()) }
            .getOrNull()
            ?.let { inferYearForWeekday(it, parseGermanWeekdayAbbreviation(weekday), clock) }

    private companion object {
        /** The WordPress post id on the article, the night's only stable identity. */
        val EVENT_ID_PATTERN = Regex("""^event-(\d+)$""")

        /** The `id` prefix, reused to build the homepage anchor URL. */
        const val EVENT_ID_PREFIX = "event-"

        /** The rule the venue draws above each floor name. */
        val FLOOR_RULE = Regex("""_{3,}""")

        /** A `by <presenter>` tail on a floor name, dropped so the same floor groups across nights. */
        val FLOOR_PRESENTER_SUFFIX = Regex("""\s+by\s+.*$""", RegexOption.IGNORE_CASE)

        /**
         * A floor-block line that opens with a plus is a programme note, never an act, and one that
         * opens with `by` is a floor credit that wrapped onto its own line (`by Kater`, #1843). A line
         * that names the venue is its own programme (`GYMie Kater + Rave Olympiad`, #1843).
         */
        val NOTE_LINE = Regex("""^\+\s|^by\s+\S|\bkater\b""", RegexOption.IGNORE_CASE)

        /**
         * `Saturday by Muting The Noise`, `Sunday by 257`: a weekday and the collective hosting that
         * night's floor. The host is a booking, never a billed act, and the weekday anchors the rule
         * so a line like `Nina by Night` is untouched. A bare `Saturday night` heads a floor the
         * same way (#1843).
         */
        val WEEKDAY_HEADING =
            Regex("""^(?:mon|tues|wednes|thurs|fri|satur|sun)day(?:\s+by\s+\S|(?:\s+night)?\s*$)""", RegexOption.IGNORE_CASE)

        /** A bracketed performance-format marker appended to an act ("Vovolectr0 [LIVE]"). */
        val FORMAT_MARKER = Regex("""\s*\[[^\]]*]\s*$""")

        /** Capture-group indices of [SCHEDULE_PATTERN]; the closing half's groups are empty when the line has none. */
        const val WEEKDAY_GROUP = 1
        const val DAY_GROUP = 2
        const val MONTH_GROUP = 3
        const val TIME_GROUP = 4
        const val END_WEEKDAY_GROUP = 5
        const val END_DAY_GROUP = 6
        const val END_MONTH_GROUP = 7
        const val END_TIME_GROUP = 8

        /** One `Wd. DD.MM HH:mm` half of the schedule line. */
        private const val HALF = """([A-Za-zÄÖÜäöü]{2})\.\s*(\d{1,2})\.(\d{1,2})\.?\s+(\d{1,2}:\d{2})"""

        /** The opening schedule line: a start half, and usually a closing half after a dash of any width. */
        val SCHEDULE_PATTERN = Regex("""^$HALF(?:\s*[—–-]\s*$HALF)?""")

        /**
         * An unambiguous free-entry phrase in the blurb. Multi-word on purpose: the shared
         * [detectFree][de.norm.events.scraper.detectFree] also accepts a bare `free`, which "free
         * drinks" would trip. The lookahead rejects a **time-limited** offer — the Tuesday residency
         * writes "free entry till 20:00", not a free event.
         */
        val FREE_ENTRY_PHRASE =
            Regex("""(?:free entry|eintritt frei|freier eintritt)(?!\s+(?:till|until|before|bis|ab)\b)""", RegexOption.IGNORE_CASE)
    }
}
