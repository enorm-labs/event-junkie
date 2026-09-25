package de.norm.events.scraper.renate

import de.norm.events.event.EventType
import de.norm.events.scraper.B2B_SEPARATOR
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hostedActsFromTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseEnglishWeekdayAbbreviation
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.MonthDay

/**
 * Pure HTML parser for Renate's homepage programme.
 *
 * Each night is a `.prog-row`: a `.prog-day` weekday, a year-less `.prog-date`, a
 * `.prog-title`, the spaces in use (`.cat-btn`), a Resident Advisor `.ticket-link`, and a
 * `.prog-text` block with the per-floor lineup. The trailing `.prog-row.blog-row` is a news
 * post, excluded by requiring a date.
 *
 * **The lineup needs two guards, because the venue reuses `<strong>` for prose.** A `<strong>`
 * heading opens a floor only when it starts with one of the actual floor names
 * ([FLOOR_HEADING]) — `Garten für alle!` is a slogan, `hosted by Neer` a continuation of the
 * heading above, `House of Lunacy presents THE VILLAGE` a festival blurb, none a floor. And a
 * line beneath a floor is an act only when short enough to be a name ([MAX_ACT_WORDS]); the
 * venue mixes workshop schedules and multi-sentence policy text into the same block.
 *
 * **A single-space night names no floor at all** — SENSUS lists eleven DJs under a bare `CLUB`
 * badge with no heading (#1582). Then the one `.cat-btn` is the stage, but only when every line
 * reads as a name: a night describing itself in prose under the same badge (House of Lunacy)
 * still yields nothing.
 *
 * @see RenateWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.renate.cc/">Renate Berlin</a>
 */
class RenateOverviewPageScraper(
    /** Clock for the year inference, in the venue's time zone; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event rows from the homepage, in listing order.
     *
     * @param baseUrl the URL the document was fetched from, stored as each event's
     * [ScrapedEvent.sourceUrl] — no per-event page.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        // A `.prog-row.blog-row` carries a news post; requiring a date keeps only real nights.
        val rows = document.select(".prog-row:has(.prog-date)")
        logger.info { "Found ${rows.size} event row(s) on Renate homepage" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed rows without aborting the whole import
        return rows.mapNotNull { row ->
            try {
                parseRow(row, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Renate row, skipping" }
                null
            }
        }
    }

    /** Parses one `.prog-row` into a [ScrapedEvent], or `null` without a title or usable date. */
    @Suppress("ReturnCount") // Guard clauses for the required title/date are clearer than nesting
    private fun parseRow(
        row: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val title = row.textAt(".prog-title")?.let(::cleanEventTitle) ?: return null
        val eventDate = parseRowDate(row)
        if (eventDate == null) {
            logger.warn { "No parseable date for Renate event '$title', skipping" }
            return null
        }

        return ScrapedEvent(
            title = title,
            // A techno club stating no category; `.cat-btn` names the spaces in use (CLUB / GARTEN), not a
            // kind of event.
            eventType = EventType.PARTY.name,
            // The venue names no style but programmes techno and house, so the venue is the default, as at Tresor.
            genre = "Techno, House",
            eventDate = eventDate,
            // No per-event page, so every night points at the programme and takes its identity from date
            // plus slugified title.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.RENATE.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = row.hrefAt(".ticket-link"),
            artists = parseLineup(row).ifEmpty { hostedActsFromTitle(title, role = "DJ") }
        )
    }

    /**
     * The row's `Thu.` / `06.08.` date pair, year from the weekday: the programme prints no year
     * and runs across the turn of the year ([inferYearForWeekday]).
     */
    @Suppress("ReturnCount") // Guard clauses for the missing / unparseable date parts are clearer than nesting
    private fun parseRowDate(row: Element): LocalDate? {
        val match = DATE_PATTERN.find(row.textAt(".prog-date").orEmpty()) ?: return null
        val monthDay =
            runCatching { MonthDay.of(match.groupValues[MONTH_GROUP].toInt(), match.groupValues[DAY_GROUP].toInt()) }
                .getOrNull() ?: return null
        // The weekday is English on this page and German on some rows.
        val day = row.textAt(".prog-day")?.trim(' ', '.')
        val weekday = parseEnglishWeekdayAbbreviation(day) ?: parseGermanWeekdayAbbreviation(day)
        return inferYearForWeekday(monthDay, weekday, clock)
    }

    /**
     * The night's DJs grouped by floor. The markup is inconsistent: most nights put each floor
     * heading and act in its own paragraph, some pack a whole night — headings included — into one
     * paragraph split by `<br>`. So the block is flattened to an ordered run of lines and the floor
     * switches whenever a line *names* a floor.
     *
     * A paragraph that is nothing but a non-floor `<strong>` is skipped outright: the slogan
     * (`Garten für alle!`), a heading continuation (`hosted by Neer`) and festival blurbs, the
     * first two short enough to pass the act-line guard. The shared policy block (`.info-text`) is
     * excluded for the same reason.
     */
    private fun parseLineup(row: Element): List<ScrapedArtist> {
        val text = row.selectFirst(".prog-text") ?: return emptyList()
        val lines = lineupLines(text)
        val artists = mutableListOf<ScrapedArtist>()
        var stage: String? = if (lines.none { floorNameOf(it) != null }) bareLineupStage(row, lines) else null

        for (line in lines) {
            val floor = floorNameOf(line)
            val current = stage
            when {
                floor != null -> {
                    stage = floor
                }

                current != null && isActLine(line) -> {
                    artists += splitActs(line).map { ScrapedArtist(name = it, role = "DJ", stage = current) }
                }
            }
        }
        // An act billed on two floors of one night would produce two `event_artist` rows for one
        // (event, artist) pair and hit the unique constraint, failing the whole import — first billing
        // wins, keeping its floor.
        return artists.distinctBy { it.name.lowercase() }
    }

    /**
     * The stage for a block with no floor heading: the night's only space badge, when the block is
     * nothing but names. `null` for a multi-space night (which floor?) or for prose.
     */
    private fun bareLineupStage(
        row: Element,
        lines: List<String>
    ): String? {
        val badges = row.select(".cat-btn").map { it.text().trim().uppercase() }.filter { it.isNotBlank() }
        val names = lines.filter { it.isNotBlank() }
        val readsAsLineup = names.size >= MIN_BARE_LINEUP_LINES && names.all { isActLine(it) && !SENTENCE_END.containsMatchIn(it) }
        return badges.singleOrNull()?.takeIf { readsAsLineup }
    }

    /**
     * The lineup block's paragraphs flattened into one ordered run of lines. A paragraph that is
     * only a `<strong>` heading contributes that heading, and only when it names a floor —
     * otherwise it is the slogan, a host credit or a festival blurb, all short enough to survive
     * the act-line guard. Every other paragraph contributes its `<br>`-split lines, which makes
     * one-paragraph nights parse like paragraph-per-act ones.
     */
    private fun lineupLines(text: Element): List<String> =
        text
            .select("p")
            .filter { it.closest(".info-text") == null }
            .flatMap { paragraph ->
                val whole = paragraph.text().trim()
                if (paragraph.selectFirst("strong")?.text()?.trim() == whole) {
                    listOfNotNull(whole.takeIf { floorNameOf(it) != null })
                } else {
                    paragraph.textLines()
                }
            }

    /** The floor a heading or line names, or `null`. */
    private fun floorNameOf(line: String): String? =
        FLOOR_HEADING
            .find(line)
            ?.value
            ?.trim()
            ?.uppercase()

    /**
     * Whether [line] is an act name rather than prose, a schedule note or an unfilled slot. The
     * venue mixes all of them into the same run as its DJs and marks none differently:
     * - longer than [MAX_ACT_WORDS] words is prose (a policy sentence, a festival blurb);
     * - a clock time is a schedule line ("Workshops starting from 16:00"), not a performer;
     * - a `hosted by …` line credits the collective curating a floor — both as a heading
     * continuation and inside a run of `<br>`-split lines;
     * - the venue's "+ more tba" placeholder names nobody yet.
     *
     * The cost: an unusually wordy billing is dropped rather than mangled.
     */
    private fun isActLine(line: String): Boolean =
        line.isNotBlank() &&
            line.split(WHITESPACE).size <= MAX_ACT_WORDS &&
            !CLOCK_TIME.containsMatchIn(line) &&
            !HOST_CREDIT.containsMatchIn(line) &&
            !isUnannouncedAct(line)

    /** True when [line] is the venue's "more acts to come" placeholder rather than a name. */
    private fun isUnannouncedAct(line: String): Boolean = isNonArtistName(line) || isNonArtistName(line.replaceFirst(MORE_PREFIX, "").trim())

    /**
     * Splits an act line at `b2b` and at safe `&`/`and`/`und` boundaries, leaving a parenthesised
     * name whole — Kater's rule, where the brackets hold a duo's members.
     */
    private fun splitActs(line: String): List<String> =
        (if (line.contains('(')) listOf(line) else line.split(B2B_SEPARATOR).flatMap(::splitSegmentOnConjunctions))
            .map { it.trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }

    private companion object {
        /**
         * The actual floor names, matched at the start of a `<strong>` heading. Curated because
         * `<strong>` is also the slogan (`Garten für alle!` — the garden floor is spelled `GARDEN`, so
         * the German spelling is deliberately absent), a continuation line (`hosted by Neer`) and
         * festival prose. `TOP SECRET` precedes `SECRET` so the longer name wins.
         */
        val FLOOR_HEADING = Regex("""^(top secret|secret|garden|green|black|red)\b""", RegexOption.IGNORE_CASE)

        /** Longest an act line may be before it reads as prose rather than a name. */
        const val MAX_ACT_WORDS = 6

        /** Fewer lines than this under a bare badge is a note, not a line-up. */
        const val MIN_BARE_LINEUP_LINES = 3

        val SENTENCE_END = Regex("""[.!?:,]$""")

        /** A `hosted by …` credit for the collective curating a floor. */
        val HOST_CREDIT = Regex("""\bhosted\s+by\b""", RegexOption.IGNORE_CASE)

        /** A clock time, which marks a schedule line rather than a performer. */
        val CLOCK_TIME = Regex("""\d{1,2}:\d{2}""")

        /** The venue's "more acts to come" prefix, stripped before the shared placeholder check. */
        val MORE_PREFIX = Regex("""^\+?\s*more\s+""", RegexOption.IGNORE_CASE)

        /** The `06.08.` day/month pair; the programme prints no year. */
        val DATE_PATTERN = Regex("""(\d{1,2})\.(\d{1,2})\.""")

        const val DAY_GROUP = 1
        const val MONTH_GROUP = 2
    }
}
