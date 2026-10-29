package de.norm.events.scraper.studioiiii

import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.endOn
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseClock
import de.norm.events.scraper.parseEnglishWeekdayAbbreviation
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textLines
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.MonthDay

/**
 * Pure parser for the Studio IIII homepage, a Tilda page whose programme is a stack of cover
 * blocks (`t001`). Each block holds a year-less `FRI 23 OCTOBER 9PM` line, a title of one or two
 * lines and a line-up text. The year comes from the English weekday, or the nearest date without one.
 *
 * Line-up lines are split into DJs. A line with "by" credits an installation or a curator, and a
 * ticket line is a notice: both become the description. A credit line still bills the names after
 * `DJ SETS BY`, `LIVE SETS BY` or `MUSIC BY`, up to the end of that sentence. A night with only
 * credits is an exhibition. A note the page's editor forgot in the line-up bills nobody.
 *
 * Accepted limitation: `and` always splits a set list, so an act named with one (`Sun and Moon`)
 * bills as two unless it is a known single act. The venue joins its acts with `AND` in prose.
 */
class StudioIiiiOverviewPageScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val covers = document.select("div.t001__wrapper:has(.t001__uptitle)")
        logger.info { "Found ${covers.size} cover block(s) on the Studio IIII homepage" }
        return covers.mapSkippingFailures(logger, "a Studio IIII cover block") { parseCover(it, sourceUrl) }
    }

    private fun parseCover(
        cover: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val heading =
            cover
                .selectFirst(".t001__uptitle")
                ?.text()
                .orEmpty()
                .trim()
        val match = HEADING.matchEntire(heading)
        val date = match?.let { parseDate(it) }
        val titleLines =
            cover
                .selectFirst(".t001__title")
                ?.lines()
                .orEmpty()
                .map { it.replace(TRAILING_SEPARATOR, "") }
        val title = titleLines.joinToString(TITLE_SEPARATOR).blankToNull()
        if (date == null || title == null) {
            logger.warn { "Studio IIII cover '$heading' has no readable date or title, skipping" }
            return null
        }
        val (start, end) = parseTimes(match.groupValues[TIME_GROUP])
        val text =
            cover
                .selectFirst(".t001__descr")
                ?.lines()
                .orEmpty()
                .filterNot { SAVE_THE_DATE.matches(it) }
        val (notes, lineup) = text.partition { CREDIT.containsMatchIn(it) || NOTICE.containsMatchIn(it) }
        val djs = (lineupActs(lineup) + notes.flatMap(::setActs)).distinctBy { it.name }
        val cuedType = cuedType(title, notes, djs)
        return ScrapedEvent(
            title = title,
            description = notes.joinToString("\n").blankToNull(),
            eventType = cuedType ?: EventType.PARTY.name,
            typeIsFallback = cuedType == null,
            eventDate = date,
            startTime = start,
            endDate = end?.let { endOn(date, start, it) },
            endTime = end,
            imageUrl = cover.closest(".t-cover")?.attrAt(".t-cover__carrier", "data-content-cover-bg"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.STUDIO_IIII.sourceIdPrefix}$date-${SlugGenerator.slugify(titleLines.first())}",
            artists = djs
        )
    }

    private fun parseDate(match: MatchResult): LocalDate? {
        val (weekday, day, monthName) = match.destructured
        val month = Month.entries.firstOrNull { it.name.startsWith(monthName.uppercase()) } ?: return null
        return try {
            inferYearForWeekday(MonthDay.of(month, day.toInt()), parseEnglishWeekdayAbbreviation(weekday.take(WEEKDAY_LENGTH)), clock)
        } catch (_: DateTimeException) {
            null
        }
    }

    /** `9PM`, `12AM`, `8-10PM`: a start without a meridiem takes the end's, or the other one where it would start after the end. */
    private fun parseTimes(text: String): Pair<LocalTime?, LocalTime?> {
        val groups = TIME.find(text)?.groupValues ?: return null to null
        val (startHour, startMinute, startMeridiem) = groups.subList(START_GROUP, START_GROUP + CLOCK_GROUPS)
        val (endHour, endMinute, endMeridiem) = groups.subList(END_GROUP, END_GROUP + CLOCK_GROUPS)
        val end = endHour.blankToNull()?.let { parseClock("$it:${endMinute.ifEmpty { "00" }}", endMeridiem) }
        val inherited =
            when {
                startMeridiem.isNotEmpty() || endMeridiem.isEmpty() -> startMeridiem
                startHour.toInt() <= endHour.toInt() -> endMeridiem
                endMeridiem.equals("AM", ignoreCase = true) -> "PM"
                else -> "AM"
            }
        return parseClock("$startHour:${startMinute.ifEmpty { "00" }}", inherited.ifEmpty { null }) to end
    }

    /** The acts of the line-up lines, which may break a comma list across lines; a `(CLUBROOM)` tail is the act's room. */
    private fun lineupActs(
        lines: List<String>,
        role: ArtistRole = ArtistRole.DJ
    ): List<ScrapedArtist> =
        lines
            .map { unwrapLine(it.trim().trimEnd(',', '/')) }
            .filterNot(::isEditorNote)
            .joinToString(" / ")
            .split(ACT_SEPARATOR)
            .map { it.replace(MORE_TBA, "").trim() }
            .flatMap(::splitSegmentOnConjunctions)
            .mapNotNull { part ->
                val room = ROOM.find(part)
                val name = stripArtistSuffix(part.removeRange(room?.range ?: IntRange.EMPTY)).blankToNull()
                val stage =
                    room
                        ?.groupValues
                        ?.get(1)
                        ?.lowercase()
                        ?.replaceFirstChar { it.titlecase() }
                name?.takeUnless(::isNonArtistName)?.let { ScrapedArtist(name = it, role = role, stage = stage) }
            }.distinctBy { it.name }

    /**
     * The acts a credit line names after `DJ SETS BY`, `LIVE SETS BY` or `MUSIC BY`: `… AND DJ SETS BY
     * DOUNIA AND MATHIS AND DJ SALIVA.` (event 11928). The list ends at the sentence's stop, and a
     * later credit in it, `…, AN INSTALLATION BY X`, is cut off with its separator.
     */
    private fun setActs(line: String): List<ScrapedArtist> =
        SET_CREDIT.findAll(line).toList().flatMap { credit ->
            val tail = line.substring(credit.range.last + 1)
            val sentence = tail.substring(0, SENTENCE_END.find(tail)?.range?.first ?: tail.length)
            val names =
                CREDIT.find(sentence)?.let { nested ->
                    val cut = SET_SEPARATOR.findAll(sentence.substring(0, nested.range.first)).lastOrNull()
                    sentence.substring(0, cut?.range?.first ?: 0)
                } ?: sentence
            val role = if (credit.groupValues[1].equals("LIVE", ignoreCase = true)) ArtistRole.LIVE else ArtistRole.DJ
            lineupActs(listOf(names), role)
        }

    /** A line in a bracket of its own, with a closing stop, bills what the bracket holds: `(A FEAT. B & C).` (event 11929). */
    private fun unwrapLine(line: String): String {
        val unstopped = line.removeSuffix(".").takeUnless { ABBREVIATION_END.containsMatchIn(line) } ?: line
        val wrapped = unstopped.startsWith('(') && unstopped.endsWith(')') && unstopped.indexOf(')') == unstopped.lastIndex
        return if (wrapped) unwrapLine(unstopped.substring(1, unstopped.lastIndex).trim()) else unstopped
    }

    /** A note the page's editor left in the line-up, two sentences run together: `Clarify the DJ set lineupUse consistent …` (event 11928). */
    private fun isEditorNote(line: String): Boolean =
        JOINED_WORDS.containsMatchIn(line) && line.split(' ').count { LOWERCASE_WORD.matches(it) } >= MIN_NOTE_LOWERCASE_WORDS

    /** The type a cue names: credits alone for an installation, a title keyword, or a DJ line-up; `null` leaves the club's default. */
    private fun cuedType(
        title: String,
        notes: List<String>,
        djs: List<ScrapedArtist>
    ): String? =
        when {
            djs.isEmpty() && notes.any { EXHIBITION.containsMatchIn(it) } -> EventType.EXHIBITION.name
            else -> inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name } ?: EventType.PARTY.name.takeIf { djs.isNotEmpty() }
        }

    /** The `<br>` lines of a Tilda field, whose text sits in one wrapping `<div>` or directly in the field. */
    private fun Element.lines(): List<String> = (children().singleOrNull()?.takeIf { it.tagName() == "div" } ?: this).textLines()

    private companion object {
        /** `FRI 16 OCTOBER 12AM`, `sat 17 OCTOBER 8-10PM`, `31 OCT 10PM`. */
        val HEADING = Regex("""^(?:(\p{L}{3,})\.?\s+)?(\d{1,2})\.?\s+(\p{L}{3,})\.?(?:\s+(.*))?$""", RegexOption.IGNORE_CASE)
        const val TIME_GROUP = 4
        const val WEEKDAY_LENGTH = 3
        const val START_GROUP = 1
        const val END_GROUP = 4
        const val CLOCK_GROUPS = 3
        val TIME = Regex("""(\d{1,2})(?:[:.](\d{2}))?\s*([AP]M)?(?:\s*[-–]\s*(\d{1,2})(?:[:.](\d{2}))?\s*([AP]M))?""", RegexOption.IGNORE_CASE)
        val TRAILING_SEPARATOR = Regex("""\s*[-–—:]+\s*$""")
        const val TITLE_SEPARATOR = " – "
        val CREDIT = Regex("""\bby\b""", RegexOption.IGNORE_CASE)

        /** The credits that name acts; group 1 tells a live set from a DJ set. */
        val SET_CREDIT = Regex("""\b(?:(DJ|LIVE)\s+SETS?|MUSIC)\s+BY\s+""", RegexOption.IGNORE_CASE)

        /** The stop that ends a sentence: after a word of two letters that is not `FEAT.`, `JR.` and the like; or a colon or `//`. */
        val SENTENCE_END = Regex("""(?<=\p{L}{2})(?<!\b(?:feat|ft|vs|jr|sr|dr|st|mr|mrs|ms|bros|inc|co))\.(?=\s|$)|[:;]|\s//\s""", RegexOption.IGNORE_CASE)
        val SET_SEPARATOR = Regex("""\s*,\s*|\s+(?:&|and)\s+""", RegexOption.IGNORE_CASE)
        val NOTICE = Regex("""^(?:tickets?|free|entry|doors?|sold\s+out|cancell?ed)\b""", RegexOption.IGNORE_CASE)
        val SAVE_THE_DATE = Regex("""save\s+the\s+date\.?""", RegexOption.IGNORE_CASE)
        val EXHIBITION = Regex("""\b(?:installations?|exhibition)\b""", RegexOption.IGNORE_CASE)
        val ACT_SEPARATOR = Regex("""\s+/\s+|\s*,\s*|\s+(?:feat\.?|ft\.|featuring)\s+""", RegexOption.IGNORE_CASE)

        /** A stop that ends an initial, a dotted name or an abbreviation, `J.`, `A.A.`, `Jr.`, and stays. */
        val ABBREVIATION_END = Regex("""(?:^|[\s.(])(?:\p{L}|jr|sr|dr|st|mr|mrs|ms|bros|feat|ft|vs|inc|co)\.$|\.\S+\.$""", RegexOption.IGNORE_CASE)

        /** Two words with no space between them, `lineupUse`; one lowercase letter before the capital keeps `McCarthy` and `DeWalt` out. */
        val JOINED_WORDS = Regex("""\p{Ll}{2}\p{Lu}\p{Ll}""")
        val LOWERCASE_WORD = Regex("""\p{Ll}{2,}""")
        const val MIN_NOTE_LOWERCASE_WORDS = 2
        val MORE_TBA = Regex("""\s*(?:&|\+|and)\s*more(?:\s+tba)?\.*$""", RegexOption.IGNORE_CASE)
        val ROOM = Regex("""\s*\((\p{L}*room)\)""", RegexOption.IGNORE_CASE)
    }
}
