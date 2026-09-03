package de.norm.events.scraper.renate

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import de.norm.events.slug.SlugGenerator
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
 * Pure HTML parser for Renate's homepage programme.
 *
 * Each night is a `.prog-row` holding a `.prog-day` weekday, a year-less `.prog-date`, a
 * `.prog-title`, the spaces in use (`.cat-btn`), a Resident Advisor `.ticket-link`, and a
 * `.prog-text` block with the per-floor lineup. The trailing `.prog-row.blog-row` is a news post,
 * excluded by requiring a date.
 *
 * **The lineup needs two guards, because the venue reuses `<strong>` for prose.** A `<strong>`
 * heading opens a floor only when it starts with one of the venue's actual floor names
 * ([FLOOR_HEADING]) — `Garten für alle!` is a slogan, `hosted by Neer` a continuation of the
 * heading above it, and `House of Lunacy presents THE VILLAGE` a festival blurb, none of which is
 * a floor. And a line beneath a floor is taken as an act only when it is short enough to be a name
 * ([MAX_ACT_WORDS]); the venue mixes workshop schedules and multi-sentence policy text into the
 * same block, and those would otherwise be stored as DJs.
 *
 * @see RenateWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.renate.cc/">Renate Berlin</a>
 */
class RenateOverviewPageScraper(
    /** Clock for the year inference. Defaults to the venue's own time zone; override in tests for determinism. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event rows from the homepage document.
     *
     * @param baseUrl the URL the document was fetched from, stored as each event's
     *   [ScrapedEvent.sourceUrl] — the venue publishes no per-event page.
     * @return a list of [ScrapedEvent] instances, in listing order.
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

    /** Parses one `.prog-row` into a [ScrapedEvent], or `null` when it has no title or usable date. */
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
            // Renate is a techno club and states no category; `.cat-btn` names the spaces in use
            // (CLUB / GARTEN), not a kind of event.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            // No per-event page exists, so every night points at the programme and takes its
            // identity from the date plus the slugified title.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.RENATE.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = row.hrefAt(".ticket-link"),
            artists = parseLineup(row)
        )
    }

    /**
     * Reads the row's `Thu.` / `06.08.` date pair and infers its year from the weekday.
     *
     * The programme prints no year anywhere, and it runs across the turn of the year, so the
     * weekday is what disambiguates ([inferYearForWeekday]).
     */
    @Suppress("ReturnCount") // Guard clauses for the missing / unparseable date parts are clearer than nesting
    private fun parseRowDate(row: Element): LocalDate? {
        val match = DATE_PATTERN.find(row.textAt(".prog-date").orEmpty()) ?: return null
        val monthDay =
            runCatching { MonthDay.of(match.groupValues[MONTH_GROUP].toInt(), match.groupValues[DAY_GROUP].toInt()) }
                .getOrNull() ?: return null
        val weekday = GERMAN_ENGLISH_WEEKDAYS[row.textAt(".prog-day")?.trim(' ', '.')?.lowercase()]
        return inferYearForWeekday(monthDay, weekday, clock)
    }

    /**
     * Reads the night's DJs, grouped by the floor they play on.
     *
     * The markup is not consistent across nights: most put each floor heading and each act in its
     * own paragraph, but some pack a whole night — headings included — into one paragraph split by
     * `<br>`. So the block is flattened to an ordered run of lines and the floor is switched
     * whenever a line *names* a floor, whichever shape produced it.
     *
     * A paragraph that is nothing but a non-floor `<strong>` is skipped outright rather than read
     * as a line: those are the venue's slogan (`Garten für alle!`), a continuation of the heading
     * above (`hosted by Neer`) and festival blurbs, and the first two are short enough to pass the
     * act-line guard. The shared policy block (`.info-text`) is excluded for the same reason.
     */
    private fun parseLineup(row: Element): List<ScrapedArtist> {
        val text = row.selectFirst(".prog-text") ?: return emptyList()
        val artists = mutableListOf<ScrapedArtist>()
        var stage: String? = null

        for (line in lineupLines(text)) {
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
        // An act billed on two floors of one night would otherwise produce two `event_artist` rows
        // for the same (event, artist) pair and hit that table's unique constraint, failing the
        // whole import — so the first billing wins, keeping its floor.
        return artists.distinctBy { it.name.lowercase() }
    }

    /**
     * The lineup block's paragraphs flattened into one ordered run of lines.
     *
     * A paragraph that is nothing but a `<strong>` heading contributes only that heading, and only
     * when it names a floor — otherwise it is the venue's slogan, a host credit or a festival
     * blurb, all of which are short enough to survive the act-line guard if let through. Every
     * other paragraph contributes its `<br>`-split lines, which is what makes the one-paragraph
     * nights parse the same as the paragraph-per-act ones.
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

    /** The floor a heading or line names, or `null` when it names none. */
    private fun floorNameOf(line: String): String? =
        FLOOR_HEADING
            .find(line)
            ?.value
            ?.trim()
            ?.uppercase()

    /**
     * Whether [line] is an act name rather than prose, a schedule note or an unfilled slot.
     *
     * Three rejections, because the venue mixes all of them into the same paragraph run as its
     * DJs and marks none of them differently:
     * - anything longer than [MAX_ACT_WORDS] words is prose (a policy sentence, a festival blurb);
     * - anything carrying a clock time is a schedule line ("Workshops starting from 16:00"), not a
     *   performer;
     * - a `hosted by …` line credits the collective curating a floor, not an act — it appears both
     *   as a continuation of a floor heading and inside a run of `<br>`-split lines;
     * - the venue's own "+ more tba" placeholder, which names nobody yet.
     *
     * The cost is that an unusually wordy billing is dropped rather than mangled.
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
     * Splits an act line at a `b2b` marker and at safe `&`/`and`/`und` boundaries, leaving a
     * parenthesised name whole — the same rule as Kater, where the brackets hold a duo's members.
     */
    private fun splitActs(line: String): List<String> =
        (if (line.contains('(')) listOf(line) else line.split(B2B_SEPARATOR).flatMap(::splitSegmentOnConjunctions))
            .map { it.trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }

    private companion object {
        /**
         * The venue's actual floor names, matched at the start of a `<strong>` heading. Curated
         * because `<strong>` is also the venue's slogan (`Garten für alle!` — note the garden floor
         * is spelled `GARDEN`, so the German spelling is deliberately absent), a continuation line
         * (`hosted by Neer`) and festival prose. `TOP SECRET` precedes `SECRET` so the longer name
         * wins.
         */
        val FLOOR_HEADING = Regex("""^(top secret|secret|garden|green|black|red)\b""", RegexOption.IGNORE_CASE)

        /** Longest an act line may be before it reads as prose rather than a name. */
        const val MAX_ACT_WORDS = 6

        /** The back-to-back marker joining two DJs into one slot. */
        val B2B_SEPARATOR = Regex("""\s+b2b\s+""", RegexOption.IGNORE_CASE)

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

        /** Weekday abbreviations as the venue writes them — English on this page, German on some rows. */
        val GERMAN_ENGLISH_WEEKDAYS: Map<String, DayOfWeek> =
            mapOf(
                "mon" to DayOfWeek.MONDAY,
                "mo" to DayOfWeek.MONDAY,
                "tue" to DayOfWeek.TUESDAY,
                "di" to DayOfWeek.TUESDAY,
                "wed" to DayOfWeek.WEDNESDAY,
                "mi" to DayOfWeek.WEDNESDAY,
                "thu" to DayOfWeek.THURSDAY,
                "do" to DayOfWeek.THURSDAY,
                "fri" to DayOfWeek.FRIDAY,
                "fr" to DayOfWeek.FRIDAY,
                "sat" to DayOfWeek.SATURDAY,
                "sa" to DayOfWeek.SATURDAY,
                "sun" to DayOfWeek.SUNDAY,
                "so" to DayOfWeek.SUNDAY
            )
    }
}
