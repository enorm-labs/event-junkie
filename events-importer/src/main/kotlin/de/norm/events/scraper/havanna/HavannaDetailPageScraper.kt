package de.norm.events.scraper.havanna

import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parsePriceValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for one of Havanna Berlin's three undated weekly night pages
 * (`/wednesday`, `/friday`, `/saturday`).
 *
 * Each page is a single Squarespace rich-text block laid out the same way: a heading repeating the
 * weekday ("Friday"), a bold tagline, a heading naming the night ("Saturdays @ HAVANNA"), one
 * paragraph per dancefloor ("1st floor 22:00" + its genres), and closing paragraphs with the start
 * time, door price, and dance-lesson note. There is no date anywhere and no per-event markup — the
 * whole programme is editorial prose — so parsing works paragraph-by-paragraph off that layout, and
 * the weekday comes from the URL path rather than the heading that repeats it.
 *
 * A separate rich-text block above the programme may carry a closure notice ("WIR SIND AB DEM
 * 01.07.2026 IN DER SOMMERPAUSE!"); its date is captured so the importer can suppress occurrences
 * during the break. The programme block is identified by containing paragraphs, so notice text never
 * affects which block is read as the programme.
 *
 * @see HavannaWeeklyNight for the undated model this produces and its expansion into dated events.
 * @see <a href="https://www.havanna-berlin.de/friday">Havanna Friday</a>
 */
@Suppress("TooManyFunctions") // Cohesive single-responsibility parser; the prose layout needs several small field extractors.
class HavannaDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a night page into its undated [HavannaWeeklyNight] description.
     *
     * @param url the URL the document was fetched from — the source of the night's weekday and slug.
     * @return the parsed night, or `null` when the URL names no weekday or the page carries no
     *   programme block (both logged as warnings).
     */
    @Suppress("ReturnCount") // Guard clauses for the required weekday/programme block are clearer than nesting.
    fun scrape(
        document: Document,
        url: String
    ): HavannaWeeklyNight? {
        val dayOfWeek =
            havannaWeekdayFromUrl(url) ?: run {
                logger.warn { "Havanna page $url does not name a weekday, skipping" }
                return null
            }
        val main =
            document.selectFirst(MAIN_CONTENT) ?: run {
                logger.warn { "Havanna page $url has no main content block, skipping" }
                return null
            }

        val blocks = main.select(RICH_TEXT_BLOCK)
        // The programme block is the one holding paragraphs; a closure notice is a lone heading.
        val programme =
            blocks.firstOrNull { it.selectFirst("p") != null } ?: run {
                logger.warn { "Havanna page $url has no programme paragraphs, skipping" }
                return null
            }

        val children = programme.children()
        // Two headings: the first repeats the weekday, the second names the night. Fall back to the
        // first when a page ever carries only one.
        val headings = children.indices.filter { children[it].tagName() in HEADING_TAGS }
        val titleIndex =
            (headings.getOrNull(1) ?: headings.firstOrNull()) ?: run {
                logger.warn { "Havanna page $url has no headings, skipping" }
                return null
            }
        val title =
            children[titleIndex].text().trim().takeIf { it.isNotBlank() } ?: run {
                logger.warn { "Havanna page $url has a blank night title, skipping" }
                return null
            }

        val body = children.drop(titleIndex + 1).map { it.text().trim() }.filter { it.isNotBlank() }

        return HavannaWeeklyNight(
            dayOfWeek = dayOfWeek,
            slug = havannaNightSlug(url),
            title = title,
            subtitle = parseSubtitle(children.take(titleIndex)),
            // Keep the venue's own prose intact, one source paragraph per line — it is where the
            // floor breakdown, the dance-lesson note, and the ladies' free-entry window live.
            description = body.joinToString("\n").takeIf { it.isNotBlank() },
            genre = parseGenre(body),
            startTime = parseStartTime(body, dayOfWeek, document),
            priceBoxOffice = parseDoorPrice(body),
            imageUrl = main.imgSrcAt("img"),
            sourceUrl = url,
            pauseFrom = parseClosureDate(blocks.filter { it !== programme }, url)
        )
    }

    /** The bold tagline between the weekday heading and the night's name. */
    private fun parseSubtitle(beforeTitle: List<Element>): String? =
        beforeTitle
            .firstOrNull { it.tagName() == "p" && it.text().isNotBlank() }
            ?.text()
            ?.trim()

    /**
     * The night's genres, read off its dancefloor paragraphs and joined into one raw genre string for
     * `GenreNormalizer` to tokenize.
     *
     * A floor paragraph leads with a "1st floor" label, optionally carrying that floor's own start
     * time ("3rd floor 23:00"); the genres are whatever follows. When the venue put them in the *next*
     * paragraph instead ("4th floor 00:30" then "Charts, Top40, Discotunes"), that paragraph is used —
     * unless it is itself another floor or a price/time line, which is how a floor with no listed
     * genres ("2nd floor") correctly yields nothing.
     *
     * Parentheses are dropped because the normalizer splits on commas and `&` but not brackets, so
     * "(Reggaeton & Latin-Pop)" would otherwise leave a stray ")" welded to the last tag.
     */
    private fun parseGenre(body: List<String>): String? {
        val genres =
            body.mapIndexedNotNull { index, line ->
                val label = FLOOR_LABEL_PATTERN.find(line) ?: return@mapIndexedNotNull null
                val inline = line.substring(label.range.last + 1).trim()
                inline.ifBlank { body.getOrNull(index + 1)?.takeUnless { isFloorOrLabelLine(it) }.orEmpty() }
            }
        return genres
            .filter { it.isNotBlank() }
            .joinToString(", ") { it.replace(BRACKET_PATTERN, "").trim() }
            .takeIf { it.isNotBlank() }
    }

    /** Whether a paragraph is another floor heading or a price/time line, rather than a floor's genres. */
    private fun isFloorOrLabelLine(line: String): Boolean =
        FLOOR_LABEL_PATTERN.containsMatchIn(line) ||
            PRICING_LABEL_PATTERN.containsMatchIn(line) ||
            CLOCK_TIME_PATTERN.containsMatchIn(line)

    /**
     * When the party starts: an explicit "Start:" / "Party:" line where the page states one, otherwise
     * the weekday's opening time from the site-wide footer hours ("Saturday 22:00 – open end"), which
     * every page carries.
     */
    private fun parseStartTime(
        body: List<String>,
        dayOfWeek: DayOfWeek,
        document: Document
    ): LocalTime? =
        body.firstNotNullOfOrNull { line ->
            START_LABEL_PATTERN.find(line)?.let { match ->
                val (_, hour, minute) = match.destructured
                toLocalTime(hour, minute)
            }
        } ?: parseOpeningHours(document)[dayOfWeek]

    /**
     * The venue's opening hours from the footer, as a weekday → opening-time map.
     *
     * The footer repeats "Wednesday 20:00 – open end / Friday 22:00 – open end / Saturday 22:00 – open
     * end" on every page, which is the only start time some nights state at all.
     */
    private fun parseOpeningHours(document: Document): Map<DayOfWeek, LocalTime> {
        val footer = document.selectFirst("#footer")?.text() ?: return emptyMap()
        return WEEKDAY_TIME_PATTERN
            .findAll(footer)
            .mapNotNull { match ->
                val (name, hour, minute) = match.destructured
                val day = havannaWeekdayFromName(name) ?: return@mapNotNull null
                toLocalTime(hour, minute)?.let { day to it }
            }.toMap()
    }

    /** The door price from the "Entrance Fee: 14,00 €" line, ignoring the separate dance-lesson fee. */
    private fun parseDoorPrice(body: List<String>): BigDecimal? =
        body
            .firstOrNull { ENTRANCE_FEE_PATTERN.containsMatchIn(it) && !DANCE_LESSON_PATTERN.containsMatchIn(it) }
            ?.let { parsePriceValue(it) }

    /**
     * The first day of an announced closure, from a notice block outside the programme.
     *
     * A notice without a parseable date is logged rather than acted on: with no start date there is
     * nothing to suppress from, and treating a bare "Pause" as an open-ended shutdown would silently
     * erase the venue from the calendar.
     */
    private fun parseClosureDate(
        noticeBlocks: List<Element>,
        url: String
    ): LocalDate? {
        val notice = noticeBlocks.map { it.text() }.firstOrNull { CLOSURE_NOTICE_PATTERN.containsMatchIn(it) } ?: return null
        val date = GERMAN_DATE_PATTERN.find(notice)?.value?.let { parseGermanDate(it) }
        if (date == null) {
            logger.warn { "Havanna closure notice on $url has no parseable date, generating occurrences anyway: '$notice'" }
        } else {
            logger.info { "Havanna closure from $date announced on $url — suppressing occurrences from that date" }
        }
        return date
    }

    /** Builds a [LocalTime] from captured hour/minute groups, or `null` when they are out of range. */
    private fun toLocalTime(
        hour: String,
        minute: String
    ): LocalTime? = runCatching { LocalTime.of(hour.toInt(), minute.toInt()) }.getOrNull()

    private companion object {
        /** Squarespace marks the page's editable body with this content field, keeping header/footer blocks out. */
        const val MAIN_CONTENT = "[data-content-field=\"main-content\"]"

        /** Squarespace's rich-text block — the page's prose lives in one or two of these. */
        const val RICH_TEXT_BLOCK = ".sqs-html-content"

        /** Heading tags the rich-text editor emits for the weekday line and the night's name. */
        val HEADING_TAGS = setOf("h1", "h2", "h3")

        /** A dancefloor label leading a paragraph, with an optional per-floor start time ("3rd floor 23:00"). */
        val FLOOR_LABEL_PATTERN =
            Regex("""^\d+\s*(?:st|nd|rd|th)\s+floor\s*(?:\d{1,2}[.:]\d{2})?\s*""", RegexOption.IGNORE_CASE)

        /** An explicit start-time line ("Start: 22:00", "Party: 20.00h"); the hour and minute are captured. */
        val START_LABEL_PATTERN =
            Regex("""\b(start|beginn|party|einlass)\b\s*:\s*(\d{1,2})[.:](\d{2})""", RegexOption.IGNORE_CASE)

        /** The footer's "Saturday 22:00 – open end" opening-hours lines; weekday, hour, and minute are captured. */
        val WEEKDAY_TIME_PATTERN =
            Regex(
                """\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+(\d{1,2}):(\d{2})""",
                RegexOption.IGNORE_CASE
            )

        /** Any clock time, used to keep a time/price paragraph from being read as a floor's genres. */
        val CLOCK_TIME_PATTERN = Regex("""\d{1,2}[.:]\d{2}""")

        /** Pricing and lesson labels, used for the same guard as [CLOCK_TIME_PATTERN]. */
        val PRICING_LABEL_PATTERN =
            Regex("""€|\b(entrance|eintritt|lesson|tanzstunde|tanzkurs)\b""", RegexOption.IGNORE_CASE)

        /** The door-price line's label. */
        val ENTRANCE_FEE_PATTERN = Regex("""\b(entrance\s+fee|eintritt)\b""", RegexOption.IGNORE_CASE)

        /** The separately-priced dance lesson, excluded from the door price. */
        val DANCE_LESSON_PATTERN = Regex("""\b(dance\s+lessons?|tanzstunde|tanzkurs)\b""", RegexOption.IGNORE_CASE)

        /** Words announcing that the club is shut for a while. */
        val CLOSURE_NOTICE_PATTERN =
            Regex("""\b(sommerpause|winterpause|betriebspause|pause|geschlossen|closed)\b""", RegexOption.IGNORE_CASE)

        /** A German `DD.MM.YYYY` date, as written in the closure notice. */
        val GERMAN_DATE_PATTERN = Regex("""\d{1,2}\.\d{1,2}\.\d{4}""")

        /** Brackets around a floor's genres, dropped so they don't leak into a genre tag. */
        val BRACKET_PATTERN = Regex("""[()\[\]]""")
    }
}
