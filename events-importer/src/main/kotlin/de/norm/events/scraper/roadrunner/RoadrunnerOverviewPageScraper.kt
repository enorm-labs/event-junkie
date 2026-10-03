package de.norm.events.scraper.roadrunner

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.labelledClockPattern
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanWeekday
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * Pure HTML parser for Roadrunner's Paradise' retro `programm.html` event page.
 *
 * Hand-coded HTML from the early 2000s with **no semantic structure**: every event is a flat run
 * of `<p>` paragraphs, separated by paragraphs containing only a row of dots (". . . . ."). No
 * per-event URLs — the whole programme is one page.
 *
 * Parsing anchors on the one thing that carries meaning: the **German date line** ("Freitag,
 * 29. Mai:"). An event starts at a date line and runs until the next date line or a dotted
 * separator; the paragraphs between supply title, doors time, ticket link, flyer and description.
 *
 * The line-up sits under hand-typed labels, each on its own paragraph or in front of its acts
 * (see [parseLineup]): `Live:` and `Featuring:` bill headliners, `Support:` support acts, and
 * `Record Hop:` the night's rock'n'roll DJ (#2332). A block with a headliner label is a named
 * night ("40 Jahre Louisiana Rebs Berlin"), so its title is that name, read from the line in
 * front of the first label. A block without one keeps the first `Stil11` line as its title (see
 * [billing] for its acts). A block with no label at all bills no act, an accepted `ARTISTS`
 * limitation (#2369). `Special guests:` is not read: on the one page that used it, it named a
 * video artist and a DJ in one unseparated line.
 *
 * The `sourceId` is the date plus the first `Stil11` line, which was the title before #2332. A
 * named night keeps the key it had when its act line was the title, so the change re-keys no row.
 *
 * Dates carry a weekday but **no year**, so the year comes from the weekday: among nearby
 * candidate years, the one whose 29 May falls on the stated Friday and lands closest to today
 * (see [inferYear]). Stale past events often stay listed; they are dropped centrally at
 * persistence (`EventUpsertService`), so this parser returns every dated block as-is.
 *
 * @see RoadrunnerWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="http://www.roadrunners-paradise.de/programm.html">Roadrunner's Paradise programme</a>
 */
@Suppress("TooManyFunctions") // Cohesive single-responsibility parser; the retro markup needs many small field extractors
class RoadrunnerOverviewPageScraper(
    /** Clock for year inference; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the programme page, one per dated block.
     *
     * @param baseUrl the URL the document was fetched from, for the relative flyer path and as
     * each event's `sourceUrl`.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val blocks = splitIntoEventBlocks(document.select("p"))
        logger.info { "Found ${blocks.size} event block(s) on Roadrunner programme" }

        val parsed =
            blocks.mapSkippingFailures(logger, "event block") { block ->
                parseBlock(block, baseUrl)
            }

        return parsed
    }

    /**
     * Groups the flat paragraph list into per-event blocks. A [date line][isDateLine] opens a
     * block, which absorbs following paragraphs until the next date line or a [dotted
     * separator][isSeparator]. Paragraphs before the first date line (header) and after the last
     * separator (footer) belong to no block and are dropped.
     */
    @Suppress("DoubleMutabilityForCollection") // The segmenter both starts a block (reassign) and extends it (mutate).
    private fun splitIntoEventBlocks(paragraphs: List<Element>): List<List<Element>> {
        val blocks = mutableListOf<List<Element>>()
        var current: MutableList<Element>? = null

        for (p in paragraphs) {
            when {
                isSeparator(p) -> {
                    current?.let { blocks.add(it) }
                    current = null
                }

                isDateLine(p) -> {
                    current?.let { blocks.add(it) }
                    current = mutableListOf(p)
                }

                else -> {
                    current?.add(p)
                }
            }
        }
        current?.let { blocks.add(it) }
        return blocks
    }

    /** Parses one event block (a date line plus its following paragraphs) into a [ScrapedEvent]. */
    @Suppress("ReturnCount") // Guard clauses for the required date/title are clearer than nesting
    private fun parseBlock(
        block: List<Element>,
        baseUrl: String
    ): ScrapedEvent? {
        val dateLine = block.firstOrNull { isDateLine(it) } ?: return null
        val eventDate =
            parseGermanDate(dateLine.text()) ?: run {
                logger.warn { "Could not parse date from '${dateLine.text()}', skipping block" }
                return null
            }

        val keyLine = parseTitle(block)
        if (keyLine.isNullOrBlank()) {
            logger.warn { "Event on $eventDate has no title, skipping" }
            return null
        }

        val lineup = parseLineup(block)
        val billedActs = lineup.flatMap { it.acts }
        val hasHeadliner = billedActs.any { it.role == HEADLINER }
        val nightName = if (hasHeadliner) parseNightName(block, dateLine, lineup) else null
        val title = nightName?.text()?.trim() ?: keyLine
        val eventType = inferConcertVenueType(title)

        val doorsTime = parseDoorsTime(block)
        val ticketUrl = block.firstNotNullOfOrNull { it.selectFirst("a[href^=http]")?.attr("href") }
        // Flyer file names are typed by hand and may carry a space ("Images/Programm/The lazys.jpg"),
        // which URI.resolve rejects — an unusable flyer must not cost the whole event (#1130).
        val imageUrl =
            block
                .firstNotNullOfOrNull { it.selectFirst("img[src]") }
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { resolveUrl(baseUrl, it.replace(" ", "%20")) }.getOrNull() }
        val lineupParagraphs = lineup.flatMap { it.paragraphs } + listOfNotNull(nightName)
        val description = parseDescription(block, dateLine, title, lineupParagraphs)

        return ScrapedEvent(
            title = title,
            description = description,
            // No category field; infer from the title (concert by default for this live-music venue). See
            // inferConcertVenueType.
            eventType = eventType,
            // The venue names no style but books rock'n'roll, rockabilly and blues-rock, so the venue is the default.
            eventDate = eventDate,
            doorsTime = doorsTime,
            imageUrl = imageUrl,
            // No per-event URLs on this single-page site — the programme page is the source.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.ROADRUNNER.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(keyLine)}",
            ticketUrl = ticketUrl,
            artists = billing(title, description, billedActs)
        )
    }

    /**
     * The event's acts. A named night bills its labelled acts. A `Support:` label without a
     * headliner label confirms that the title is the act, the "title = headliner + Support:"
     * convention. A block with neither bills only its DJ, if any: an unlabelled title is as often
     * a night ("BOWIE 10", a band battle) as an act with its tour name run on, and the shared title
     * rules bill both whole. Even a stripped tour tail leaves a misspelled act ("PHIL CAMPELL'S
     * BASTARD SONS"), so `ROADRUNNER_LIMITATIONS` declares the gap instead (#2369).
     */
    private fun billing(
        title: String,
        description: String?,
        billedActs: List<ScrapedArtist>
    ): List<ScrapedArtist> =
        when {
            billedActs.any { it.role == HEADLINER } -> billedActs
            billedActs.any { it.role == SUPPORT } -> headlinersFromTitle(title, description = description) + billedActs
            else -> billedActs
        }

    /** The event title, in the first `Stil11` element, with the plain bold title as fallback. */
    private fun parseTitle(block: List<Element>): String? =
        block
            .firstNotNullOfOrNull { titleElement(it) }
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: block
                .firstNotNullOfOrNull { it.selectFirst("strong") }
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

    /**
     * The labelled line-up, one [LineupEntry] per label in page order. A label's acts follow it on
     * the same paragraph (`Live: UNSTRUT, BOXI BARRÉ`) or fill the next paragraph with text
     * (`Live:` then `THE JETS (UK) + SMOKESTACK LIGHTNIN’`). A paragraph that opens with `+` after
     * that continues the list. A bracketed note on its own line ("(Celtic Punk from Australia)")
     * is not an act.
     */
    private fun parseLineup(block: List<Element>): List<LineupEntry> {
        val entries = mutableListOf<LineupEntry>()
        var index = 0
        while (index < block.size) {
            val match = LINEUP_LABEL.matchEntire(block[index].text().trim())
            if (match == null) {
                index++
                continue
            }
            val paragraphs = mutableListOf(block[index])
            val inline = match.groupValues[2].trim()
            index++
            if (inline.isEmpty()) {
                block.getOrNull(index)?.takeIf { isActLine(it) }?.let {
                    paragraphs.add(it)
                    index++
                }
            }
            while (index < block.size && block[index].text().trim().startsWith("+")) {
                paragraphs.add(block[index++])
            }
            val role = LINEUP_ROLES.getValue(match.groupValues[1].lowercase().replace(WHITESPACE, " "))
            val text = (listOf(inline) + paragraphs.drop(1).map { it.text() }).joinToString(" + ")
            entries += LineupEntry(paragraphs, splitActs(text).map { ScrapedArtist(name = it, role = role) })
        }
        return entries
    }

    /** A paragraph that can carry a bare label's acts: text that is no label, doors line, link, flyer or note. */
    private fun isActLine(p: Element): Boolean {
        val text = p.text().trim()
        return text.isNotBlank() && !isDotsOnly(text) && !LINEUP_LABEL.matches(text) && !DOORS_LINE.containsMatchIn(text) &&
            !text.startsWith("(") && p.selectFirst("a[href^=http], img") == null
    }

    /**
     * Splits one label's acts. The page often runs two acts together with only an origin tag
     * between them (`KEITH DUNN (USA) SAUDIA YOUNG (USA)`), so a closing bracket ends an act too.
     * The origin tag and any dee-jay prefix (`Dee-jay: Red Rockin'`) come off the name.
     */
    private fun splitActs(text: String): List<String> =
        text
            .split(AFTER_BRACKET)
            .flatMap { splitSupportActs(it) }
            .map { stripArtistSuffix(it.replace(DJ_PREFIX, "").replace(SINGLE_LETTER_ORIGIN, "").trim()) }
            .filter { it.isNotBlank() && !isNonArtistName(it) }

    /**
     * The night's name in front of the first label: the last text line between the date and that
     * label ("40 Jahre Louisiana Rebs Berlin"). Null when the label follows the date directly.
     */
    private fun parseNightName(
        block: List<Element>,
        dateLine: Element,
        lineup: List<LineupEntry>
    ): Element? {
        val firstLabel = block.indexOfFirst { it === lineup.first().paragraphs.first() }
        val start = block.indexOfFirst { it === dateLine } + 1
        if (firstLabel <= start) return null
        return block
            .subList(start, firstLabel)
            .lastOrNull { it.text().isNotBlank() && !isDotsOnly(it.text()) && it.selectFirst("a[href^=http], img") == null }
    }

    /** One label's paragraphs (the label and its act lines) and the acts read from them. */
    private class LineupEntry(
        val paragraphs: List<Element>,
        val acts: List<ScrapedArtist>
    )

    /** Doors time from the "Einlass: HH:mm Uhr" paragraph. */
    private fun parseDoorsTime(block: List<Element>): LocalTime? = block.firstNotNullOfOrNull { labelledClock(it.text(), DOORS_LABELS) }

    /**
     * The block's prose paragraphs joined into the description, minus the structural lines (date,
     * title, the line-up, "Einlass…", the ticket-link line, dot separators) and image-only paragraphs.
     */
    private fun parseDescription(
        block: List<Element>,
        dateLine: Element,
        title: String,
        lineupParagraphs: List<Element>
    ): String? =
        block
            .asSequence()
            .filter { it !== dateLine }
            .filter { p -> lineupParagraphs.none { it === p } }
            .filter { titleElement(it) == null } // title line
            .filter { it.selectFirst("img") == null } // flyer-only line
            .filter { it.selectFirst("a[href^=http]") == null } // ticket-link line
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !isDotsOnly(it) }
            .filterNot { DOORS_LINE.containsMatchIn(it) }
            .filterNot { it == title }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    // -- Date parsing & year inference ------------------------------------

    /**
     * Parses a German date line like "Freitag, 29. Mai:" into a [LocalDate]: day and month from
     * the text, year [inferred][inferYear] from the weekday. `null` when day/month cannot be parsed.
     */
    private fun parseGermanDate(text: String): LocalDate? {
        val match = DATE_PATTERN.find(text) ?: return null
        val (weekdayName, day, month) = match.destructured
        val weekday = parseGermanWeekday(weekdayName)
        return parseGermanMonthDay(day, month)?.let { inferYearForWeekday(it, weekday, clock) }
    }

    /** Parses "29" + "Mai" (or a zero-padded "05") into a [MonthDay], or `null` when unparseable. */
    private fun parseGermanMonthDay(
        day: String,
        month: String
    ): MonthDay? = runCatching { MonthDay.parse("${day.toInt()}. $month", GERMAN_DAY_MONTH_FORMATTER) }.getOrNull()

    // -- Line classification ----------------------------------------------

    /**
     * The `Stil11` title styling, on a `<span>` inside the paragraph on some blocks and on the
     * `<p>` itself on others (autumn 2026: "BLUT & EISEN 30 JAHRE").
     */
    private fun titleElement(p: Element): Element? = if (p.hasClass(TITLE_CLASS)) p else p.selectFirst("span.$TITLE_CLASS")

    private fun isDateLine(p: Element): Boolean = DATE_PATTERN.containsMatchIn(p.text())

    /** A separator paragraph is non-empty and made up solely of dots and whitespace. */
    private fun isSeparator(p: Element): Boolean = isDotsOnly(p.text())

    private fun isDotsOnly(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && trimmed.all { it == '.' || it.isWhitespace() }
    }

    companion object {
        /** The stylesheet class the page's author gives an event's title line. */
        private const val TITLE_CLASS = "Stil11"

        /**
         * A German date line — "<Weekday>, <day>. <Month>" — capturing weekday, day number and month
         * name. The comma and the day's dot are optional: the page is typed by hand and one season
         * wrote "Samstag 05 September:" and "Samstag 31 Oktober:", which the strict form silently
         * turned into an import of zero events (#1130). The trailing colon and any following text
         * (e.g. a second date for two-day events) are ignored.
         */
        private val DATE_PATTERN =
            Regex(
                """(Montag|Dienstag|Mittwoch|Donnerstag|Freitag|Samstag|Sonntag),?\s*(\d{1,2})\.?\s*""" +
                    """(Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember)""",
                RegexOption.IGNORE_CASE
            )

        private const val HEADLINER = "HEADLINER"
        private const val SUPPORT = "SUPPORT"
        private const val DJ = "DJ"

        /** A line-up label at the start of a paragraph, capturing the label and the acts after its colon. */
        private val LINEUP_LABEL = Regex("""^(live|featuring|support|record\s+hop)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

        /** The role each [LINEUP_LABEL] bills. A record hop is a rock'n'roll DJ set. */
        private val LINEUP_ROLES = mapOf("live" to HEADLINER, "featuring" to HEADLINER, "support" to SUPPORT, "record hop" to DJ)

        private val WHITESPACE = Regex("""\s+""")

        /** The gap after a closing bracket that has more text behind it: the end of an act. */
        private val AFTER_BRACKET = Regex("""(?<=\))\s+(?=\S)""")

        /** A `Dee-jay:` or `DJ:` in front of a record hop's name. */
        private val DJ_PREFIX = Regex("""^\s*(?:dee-?jay|dj)\s*:\s*""", RegexOption.IGNORE_CASE)

        /** A one-letter origin tag (`(D)`), which the shared origin rule leaves alone. */
        private val SINGLE_LETTER_ORIGIN = Regex("""\s*\(\p{Lu}\)$""")

        /** The "Einlass: HH:mm Uhr" line, dropped from the description. */
        private val DOORS_LINE = labelledClockPattern(DOORS_LABELS)

        /** Parses "29. Mai" using full German month names, case-insensitively. */
        private val GERMAN_DAY_MONTH_FORMATTER: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("d. MMMM")
                .toFormatter(Locale.GERMAN)
    }
}
