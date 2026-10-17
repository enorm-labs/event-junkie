package de.norm.events.scraper.backsteinboot

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.endOn
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.net.URLEncoder
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure parser for Backsteinboot's Cargo programme page. Each event is a `column-unit` under a month
 * heading (`span.title`, `October 2026`): a `span.subtitle` title, a `span.date-and-time` with
 * `10 - 11.10.2026` over `23:59 - 23:59`, then `span.text` lines that open with the format in capitals
 * (`CLUB`, `TALK & DISCUSSIONS`) and carry `artists:`, `in collaboration with` and `entry:` lines.
 *
 * Workshops are dropped ([isParticipationFormat]), and so is a night `by invitation only`. The page is
 * typed by hand: a date that does not parse, or one that falls outside its heading's month, is skipped
 * with a warning. The archive prints `15 - 16.08.2025` under 2026. The poster comes from the page's
 * media list, which maps each `media-item` hash to a file name.
 */
class BacksteinbootProgrammePageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val posters = posterFiles(document)
        val blocks = programmeBlocks(document)
        logger.info { "Found ${blocks.size} block(s) on the Backsteinboot programme" }
        return blocks.mapSkippingFailures(logger, "a Backsteinboot programme block") { (heading, element) ->
            parseBlock(element, heading, sourceUrl, posters)
        }
    }

    /** Each titled `column-unit`, with the days of its month heading, or null under a heading that names no month. */
    private fun programmeBlocks(document: Document): List<Pair<ClosedRange<LocalDate>?, Element>> {
        var heading: ClosedRange<LocalDate>? = null
        val blocks = mutableListOf<Pair<ClosedRange<LocalDate>?, Element>>()
        for (element in document.select("span.title, column-unit:not(:has(column-unit))")) {
            when {
                element.tagName() == "span" -> heading = headingDays(element.text().clean())
                element.titleText() != null -> blocks += heading to element
            }
        }
        return blocks
    }

    private fun parseBlock(
        element: Element,
        heading: ClosedRange<LocalDate>?,
        sourceUrl: String,
        posters: Map<String, String>
    ): ScrapedEvent? {
        val block = readBlock(element) ?: return null
        if (!isInScope(block)) return null
        val days = readDays(block, heading) ?: return null
        return toEvent(block, days, sourceUrl, posters[element.selectFirst("media-item[hash]")?.attr("hash")])
    }

    private fun readBlock(element: Element): Block? {
        val title = element.titleText() ?: return null
        val lines = element.select("span.text").flatMap { it.lines() }
        val label = lines.firstOrNull { it.isNotBlank() }?.takeIf(::isFormatLabel)
        val dateTime =
            element
                .selectFirst("span.date-and-time")
                ?.lines()
                .orEmpty()
                .filter { it.isNotBlank() }
        return Block(title, label, lines, dateTime.firstOrNull(), dateTime.getOrNull(1))
    }

    private fun isInScope(block: Block): Boolean {
        if (isParticipationFormat(block.label)) {
            logger.debug { "Backsteinboot '${block.title}' is a ${block.label}, skipping" }
            return false
        }
        if (block.lines.any { INVITATION_ONLY.containsMatchIn(it) }) {
            logger.debug { "Backsteinboot '${block.title}' is by invitation only, skipping" }
            return false
        }
        return true
    }

    private fun readDays(
        block: Block,
        heading: ClosedRange<LocalDate>?
    ): Days? {
        val days = block.dateLine?.let(Days::parse)
        if (days == null) {
            logger.warn { "Backsteinboot '${block.title}' has no readable date ('${block.dateLine.orEmpty()}'), skipping" }
            return null
        }
        if (heading != null && days.first !in heading.start.minusMonths(1)..heading.endInclusive.plusMonths(1)) {
            logger.warn { "Backsteinboot '${block.title}' is dated ${days.first}, outside its heading, skipping" }
            return null
        }
        return days
    }

    private fun toEvent(
        block: Block,
        days: Days,
        sourceUrl: String,
        poster: String?
    ): ScrapedEvent {
        val type = eventTypeOf(block.label)
        val times = block.timeLine?.let { TIME_RANGE.find(it) }
        val start = times?.groupValues?.get(1)?.let(::parseClock)
        val end =
            times
                ?.groupValues
                ?.get(2)
                ?.let(::parseClock)
                .takeUnless { type == EventType.EXHIBITION.name }
        val entry = block.labelled(ENTRY)
        val ticketUrl = entry?.let { URL.find(it)?.value }
        val priceNote = entry?.takeIf { ticketUrl == null }
        return ScrapedEvent(
            title = block.title,
            eventType = type,
            eventDate = days.first,
            startTime = start,
            endDate = days.last ?: end?.let { endOn(days.first, start, it) },
            endTime = end,
            imageUrl = poster,
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.BACKSTEINBOOT.sourceIdPrefix}${days.first}-${SlugGenerator.slugify(block.title)}",
            ticketUrl = ticketUrl,
            priceBoxOffice = parsePriceValue(priceNote),
            priceNote = priceNote,
            artists = if (type in PERFORMANCE_TYPES) artistsOf(block, type) else emptyList(),
            promoters = if (type == EventType.PARTY.name) block.labelled(COLLABORATION)?.let(::names).orEmpty() else emptyList()
        )
    }

    private fun artistsOf(
        block: Block,
        type: String?
    ): List<ScrapedArtist> =
        block.labelled(ARTISTS)?.let(::names).orEmpty().filterNot(::isNonArtistName).map { name ->
            val act = name.replace(LIVE_SUFFIX, "")
            val role =
                if (act != name) {
                    "LIVE"
                } else if (type == EventType.PARTY.name) {
                    "DJ"
                } else {
                    "HEADLINER"
                }
            ScrapedArtist(name = act, role = role)
        }

    private fun headingDays(text: String): ClosedRange<LocalDate>? =
        try {
            YearMonth.parse(text, MONTH_HEADING).let { it.atDay(1)..it.atEndOfMonth() }
        } catch (_: DateTimeParseException) {
            null
        }

    /** The page's media list, from a `media-item` hash to its poster URL. */
    private fun posterFiles(document: Document): Map<String, String> =
        document
            .select("script")
            .flatMap { MEDIA_FILE.findAll(it.data()).toList() }
            .associate { match ->
                val (name, hash) = match.destructured
                hash to "$POSTER_BASE$hash/${URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")}"
            }

    private data class Block(
        val title: String,
        val label: String?,
        val lines: List<String>,
        val dateLine: String?,
        val timeLine: String?
    ) {
        /** The text after [label] and the lines that continue it, up to a blank line or the next `label:` line. */
        fun labelled(label: Regex): String? {
            val index = lines.indexOfFirst { label.containsMatchIn(it) }
            if (index < 0) return null
            val rest = lines.drop(index + 1).takeWhile { it.isNotBlank() && !NEXT_LABEL.containsMatchIn(it) }
            return (listOf(lines[index].replace(label, "")) + rest)
                .reduce { text, line ->
                    if (text.trimEnd().endsWith(",")) "$text $line" else "$text, $line"
                }.clean()
                .blankToNull()
        }
    }

    private data class Days(
        val first: LocalDate,
        val last: LocalDate?
    ) {
        companion object {
            /** The day or days [line] names: `04.10.2026`, `10 - 11.10.2026` or `30.10. - 01.11.2026`. */
            fun parse(line: String): Days? {
                val groups = DATE_SPAN.matchEntire(line.clean())?.groupValues ?: return null
                val fromDay = groups[FROM_DAY]
                val toMonth = groups[TO_MONTH].toInt()
                return try {
                    val to = LocalDate.of(groups[YEAR].toInt(), toMonth, groups[TO_DAY].toInt())
                    if (fromDay.isEmpty()) return Days(to, null)
                    val month = groups[FROM_MONTH].toIntOrNull() ?: toMonth
                    val from = LocalDate.of(if (month > toMonth) to.year - 1 else to.year, month, fromDay.toInt())
                    Days(from, to).takeIf { from < to }
                } catch (_: DateTimeException) {
                    null
                }
            }

            private const val FROM_DAY = 1
            private const val FROM_MONTH = 2
            private const val TO_DAY = 3
            private const val TO_MONTH = 4
            private const val YEAR = 5
        }
    }

    companion object {
        /**
         * Whether a format line names only a workshop, which visitors take part in (EVENT_SCOPE.md §3.2).
         * `WORKSHOP, SONIC NEST & CLUB` stays: the club night follows the workshop.
         */
        fun isParticipationFormat(label: String?): Boolean = label != null && "WORKSHOP" in label && eventTypeOf(label) == null

        /** The event type the venue's capitalised format line names, or null for none it maps. */
        fun eventTypeOf(label: String?): String? = label?.let { FORMATS.entries.firstOrNull { (word, _) -> word in it }?.value }

        private val FORMATS =
            linkedMapOf(
                "CLUB" to EventType.PARTY.name,
                "CONCERT" to EventType.CONCERT.name,
                "SONIC NEST" to EventType.CONCERT.name,
                "SHOW" to EventType.SHOW.name,
                "PERFORMANCE" to EventType.SHOW.name,
                "SCREENING" to EventType.SCREENING.name,
                "EXHIBITION" to EventType.EXHIBITION.name,
                "OPEN STUDIOS" to EventType.EXHIBITION.name,
                "TALK" to EventType.READING.name,
                "DISCUSSION" to EventType.READING.name,
                "READING" to EventType.READING.name,
                "LECTURE" to EventType.READING.name
            )
        private val PERFORMANCE_TYPES = setOf(EventType.PARTY.name, EventType.CONCERT.name, EventType.SHOW.name)
        private const val MAX_LABEL_LENGTH = 60
        private const val POSTER_BASE = "https://freight.cargo.site/w/1200/i/"
        private val MONTH_HEADING = DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH)
        private val CLOCK = DateTimeFormatter.ofPattern("H:mm")
        private val DATE_SPAN = Regex("""^(?:(\d{1,2})(?:\.(\d{1,2}))?\.?\s*[-–]\s*)?(\d{1,2})\.(\d{1,2})\.(\d{4})$""")
        private val TIME_RANGE = Regex("""^(\d{1,2}[:.]\d{2})(?:\s*[-–]\s*(\d{1,2}[:.]\d{2}))?""")
        private val INVITATION_ONLY = Regex("""\bby invitation only\b|\bnur auf einladung\b""", RegexOption.IGNORE_CASE)
        private val ARTISTS = Regex("""^artists?\s*:\s*""", RegexOption.IGNORE_CASE)
        private val ENTRY = Regex("""^entry\s*:\s*""", RegexOption.IGNORE_CASE)
        private val COLLABORATION = Regex("""^in collaboration with\s+""", RegexOption.IGNORE_CASE)
        private val NEXT_LABEL = Regex("""^[\p{L} ]{2,20}:""")
        private val URL = Regex("""https?://\S+""")
        private val LIVE_SUFFIX = Regex("""\s+live$""", RegexOption.IGNORE_CASE)
        private val TOP_LEVEL_COMMA = Regex(""",(?![^()]*\))""")
        private val PARENTHESES = Regex("""\s*\([^()]*\)""")
        private val MEDIA_FILE = Regex(""""name":"([^"\\]+)","hash":"([A-Za-z0-9]+)"""")

        private fun isFormatLabel(line: String): Boolean = line.length <= MAX_LABEL_LENGTH && line.any { it.isLetter() } && line == line.uppercase(Locale.ROOT)

        private fun names(text: String): List<String> = text.split(TOP_LEVEL_COMMA).mapNotNull { it.replace(PARENTHESES, "").blankToNull() }

        private fun parseClock(text: String): LocalTime? = parseTime(text.replace('.', ':'), CLOCK)

        private fun String.clean(): String = replace(WHITESPACE, " ").trim()

        private fun Element.titleText(): String? = select("span.subtitle").firstNotNullOfOrNull { it.text().clean().blankToNull() }

        /** The element's text split at each `<br>` and `<div>`; a blank line, or the `~~~` divider, marks a paragraph break. */
        private fun Element.lines(): List<String> {
            val text = StringBuilder()
            NodeTraversor.traverse(
                NodeVisitor { node, _ ->
                    when {
                        node is TextNode -> text.append(node.text())
                        node is Element && (node.tagName() == "br" || node.tagName() == "div") -> text.append('\n')
                    }
                },
                this
            )
            return text.lines().map { it.clean().trim('~') }.dropWhile { it.isEmpty() }
        }
    }
}
