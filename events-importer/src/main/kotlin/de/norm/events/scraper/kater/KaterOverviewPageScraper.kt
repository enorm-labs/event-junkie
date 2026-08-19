package de.norm.events.scraper.kater

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.isScreeningTitle
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.stripArtistSuffix
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

/** Time zone the venue programmes in — used to infer the year of its year-less dates. */
private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Pure HTML parser for Kater Berlin's homepage programme.
 *
 * The homepage is the whole source: the venue's `event` REST route exposes no ACF fields (`acf`
 * comes back empty) and its `/event/<slug>` pages carry nothing but a heading, so neither is worth
 * fetching. Each night is an `article.event[id=event-<postId>]` holding a `.date-title` name and an
 * `.entry-summary` that opens with a `Wd. DD.MM HH:mm — Wd. DD.MM HH:mm` span, then an optional
 * Resident Advisor ticket link, then free prose.
 *
 * **The prose is only sometimes a lineup, and the venue marks which.** A `____________` rule
 * introduces a floor (`HOPPER`, `ACID BOGEN`, `EXTRA`, sometimes suffixed `by <presenter>`) and the
 * lines beneath it are that floor's DJs, which is what [ScrapedArtist.stage] is for. A summary with
 * no rule is a description — a garden evening, a film night, a residency's schedule notes — and
 * yields **no** artists, rather than minting lines like "free entry till 20:00" or a film synopsis
 * as acts.
 *
 * Dates carry a weekday but no year, so the year is inferred from the weekday
 * ([inferYearForWeekday]).
 *
 * @see KaterWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.katerclub.de/">Kater Berlin</a>
 */
class KaterOverviewPageScraper(
    /** Clock for the year inference. Defaults to the venue's own time zone; override in tests for determinism. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event articles from the homepage document.
     *
     * @param baseUrl the URL the document was fetched from, used to build each event's anchor URL.
     * @return a list of [ScrapedEvent] instances, in listing order.
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

    /** Parses a single `article.event` into a [ScrapedEvent], or `null` when it has no id, title or date. */
    @Suppress("ReturnCount") // Guard clauses for the required id / title / date are clearer than nesting
    private fun parseArticle(
        article: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val eventId = EVENT_ID_PATTERN.find(article.id())?.groupValues?.get(1) ?: return null
        val title =
            article
                .selectFirst(".date-title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::cleanEventTitle)
                ?: return null

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
            // The venue publishes no per-event page worth fetching, so the homepage anchor is the URL.
            sourceUrl = "$baseUrl#$EVENT_ID_PREFIX$eventId",
            sourceId = "${EventSource.KATER.sourceIdPrefix}$eventId",
            ticketUrl = article.hrefAt("a.rsvp"),
            free = description?.let { FREE_ENTRY_PHRASE.containsMatchIn(it) } == true,
            artists = lineup
        )
    }

    /**
     * The summary's text lines, with the ticket paragraph dropped.
     *
     * The summary mixes `<p>` blocks and `<br>` breaks inconsistently — a floor rule and its name
     * may sit in one paragraph while the acts continue in the next — so every paragraph is
     * flattened into one ordered stream of lines before anything is read out of it.
     */
    private fun summaryLines(article: Element): List<String> =
        article
            .select(".entry-summary p")
            .filter { it.selectFirst("a.rsvp") == null }
            .flatMap { paragraph -> paragraph.wholeText().split('\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /**
     * Splits the summary body into floors and their acts.
     *
     * Returns empty for a summary with no `____` rule: that is the venue's own signal that the
     * prose is a description rather than a lineup, and reading it as acts would mint schedule notes
     * and film synopses as artists.
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

                stage != null -> {
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
     * Splits an act line into individual acts: at a `b2b` marker and at safe `&`/`and`/`und`
     * boundaries — but a **parenthesised** line is never split, because the brackets hold a duo's
     * member list rather than a second slot ("Double Penetration (FLOWWW b2b Joe Cleen)" is one
     * act). A trailing `[LIVE]` format marker is stripped, as is any tail
     * [stripArtistSuffix] recognises.
     */
    private fun splitActs(line: String): List<String> =
        (if (line.contains('(')) listOf(line) else line.split(B2B_SEPARATOR).flatMap(::splitSegmentOnConjunctions))
            .map { stripArtistSuffix(it.replace(FORMAT_MARKER, "").trim()) }
            .filter { it.isNotBlank() && !isNonArtistName(it) }

    /** A night's start, once its year has been inferred. */
    private data class Schedule(
        val date: LocalDate,
        val startTime: java.time.LocalTime?
    )

    /**
     * Parses the summary's opening `Wd. DD.MM HH:mm — Wd. DD.MM HH:mm` line.
     *
     * Only the **start** half is used: the model has no end-time field, and the closing half is
     * usually the following morning anyway. The date carries no year, so the weekday disambiguates
     * it via [inferYearForWeekday].
     */
    private fun parseSchedule(line: String): Schedule? {
        val groups = SCHEDULE_PATTERN.find(line)?.groupValues ?: return null
        val monthDay = runCatching { MonthDay.of(groups[MONTH_GROUP].toInt(), groups[DAY_GROUP].toInt()) }.getOrNull()
        return monthDay?.let {
            Schedule(
                date = inferYearForWeekday(it, GERMAN_WEEKDAYS[groups[WEEKDAY_GROUP].lowercase()], clock),
                startTime = parseTime(groups[TIME_GROUP])
            )
        }
    }

    private companion object {
        /** The WordPress post id on the article, which is the night's only stable identity. */
        val EVENT_ID_PATTERN = Regex("""^event-(\d+)$""")

        /** The `id` prefix, reused to build the homepage anchor URL. */
        const val EVENT_ID_PREFIX = "event-"

        /** The rule the venue draws above each floor name. */
        val FLOOR_RULE = Regex("""_{3,}""")

        /** A `by <presenter>` tail on a floor name, dropped so the same floor groups across nights. */
        val FLOOR_PRESENTER_SUFFIX = Regex("""\s+by\s+.*$""", RegexOption.IGNORE_CASE)

        /** The back-to-back marker joining two DJs into one slot. */
        val B2B_SEPARATOR = Regex("""\s+b2b\s+""", RegexOption.IGNORE_CASE)

        /** A bracketed performance-format marker the venue appends to an act ("Vovolectr0 [LIVE]"). */
        val FORMAT_MARKER = Regex("""\s*\[[^\]]*]\s*$""")

        /** Capture-group indices of [SCHEDULE_PATTERN]. */
        const val WEEKDAY_GROUP = 1
        const val DAY_GROUP = 2
        const val MONTH_GROUP = 3
        const val TIME_GROUP = 4

        /** The opening schedule line; only the start half is captured. */
        val SCHEDULE_PATTERN = Regex("""^([A-Za-zÄÖÜäöü]{2})\.\s*(\d{1,2})\.(\d{1,2})\.?\s+(\d{1,2}:\d{2})""")

        /** German weekday abbreviations as the venue writes them. */
        val GERMAN_WEEKDAYS: Map<String, DayOfWeek> =
            mapOf(
                "mo" to DayOfWeek.MONDAY,
                "di" to DayOfWeek.TUESDAY,
                "mi" to DayOfWeek.WEDNESDAY,
                "do" to DayOfWeek.THURSDAY,
                "fr" to DayOfWeek.FRIDAY,
                "sa" to DayOfWeek.SATURDAY,
                "so" to DayOfWeek.SUNDAY
            )

        /**
         * An unambiguous free-entry phrase in the blurb. Deliberately multi-word: the shared
         * [detectFree][de.norm.events.scraper.detectFree] also accepts a bare `free`, which a
         * description mentioning "free drinks" would trip.
         *
         * The lookahead rejects a **time-limited** offer — the venue's Tuesday residency writes
         * "free entry till 20:00", which is not a free event and must not be flagged as one.
         */
        val FREE_ENTRY_PHRASE =
            Regex("""(?:free entry|eintritt frei|freier eintritt)(?!\s+(?:till|until|before|bis|ab)\b)""", RegexOption.IGNORE_CASE)
    }
}
