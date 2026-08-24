package de.norm.events.scraper.gartn

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textLines
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

/** Time zone the venue programmes in — used to infer the year of its year-less dates. */
private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Pure HTML parser for gART.n's single-page Carrd programme.
 *
 * Carrd emits no semantic markup, no per-event URL and no structured data — every block is a
 * `div.container-component` whose `id` and `instance-N` class are renumbered whenever the owner edits
 * the page, so neither can identify an event. What *is* stable is Carrd's component classes plus the
 * programme's shape: an event block is a `.container-component` whose `h2` is a date heading (`SA
 * 08.08.`), which is how [DATE_HEADING] separates them from image and footer containers:
 *
 * ```
 * <h2>SA <strong>08.08.</strong></h2>   ← weekday + day.month, no year
 * <p>14:00 - 22:00</p>                  ← opening hours, then <h3> the title
 * <p><span class="p">Running Hot<br> The Office<br> Amina <sup>b2b</sup> Luqqi</span></p>
 * <ul class="buttons-component"><li><a href="https://ra.co/events/…">Tickets</a></li></ul>
 * ```
 *
 * Four things about this page shape the parser:
 * 1. **The date carries no year** but states its German weekday, so the year is inferred from it
 *    ([inferYearForWeekday]) rather than assumed — the rule Arcanoa and VOID Club also use, and what
 *    keeps a programme correct across the turn of the year.
 * 2. **The paragraphs are told apart by content, not position**: the one that is *nothing but* a time
 *    range is the opening hours, the one beside the ticket button is the ticket note, and the rest is
 *    the lineup. Carrd's wrappers carry no classes, so the alternative is a positional selector (ADR-007).
 * 3. **`<sup>` is the venue's annotation marker**, inline for a `b2b` join and a `live` set format, and
 *    once for a whole line annotating the one above it. An inline annotation is kept — `b2b` splits the
 *    slot into two acts, `live` is trimmed off by [stripArtistSuffix] — while a line that is *only* an
 *    annotation is a note rather than a billing, and is dropped with the guests it names.
 * 4. **"pre-sale sold out" does not mean sold out.** The venue pairs that label with a "more tickets
 *    at the door" note, so [ScrapedEvent.soldOut] is set only when the label says sold out and no
 *    such note accompanies it. The note is kept as the price note — the only pricing the page has.
 *
 * The venue publishes no prices, genre, per-event image, description or per-event page, and removes an
 * event once it has passed, so its identity is the date plus the slugified title. The closing time is
 * dropped: the model stores doors and start, not an end. Every lineup entry is stored as a `DJ` —
 * `ArtistRole` has no value for the page's `live` marker — and a line billing a live format rather
 * than a performer is stored as the venue bills it, since nothing separates the two.
 *
 * @see GartnWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.gartn.xyz/">gART.n Berlin</a>
 */
class GartnOverviewPageScraper(
    /** Clock for the year inference. Defaults to the venue's own time zone; override in tests for determinism. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event blocks from the programme page document.
     *
     * @param baseUrl the URL the document was fetched from, stored as each event's
     *   [ScrapedEvent.sourceUrl] — the venue publishes no per-event page.
     * @return a list of [ScrapedEvent] instances, in listing order.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val blocks = document.select("div.container-component").filter { DATE_HEADING.matches(dateHeading(it)) }
        logger.info { "Found ${blocks.size} event block(s) on the gART.n programme" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed blocks without aborting the whole import
        return blocks.mapNotNull { block ->
            try {
                parseBlock(block, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse gART.n event block, skipping" }
                null
            }
        }
    }

    /** Parses one block into a [ScrapedEvent], or `null` when it has no title or usable date. */
    @Suppress("ReturnCount") // Guard clauses for the required title/date are clearer than nesting
    private fun parseBlock(
        block: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val heading = block.selectFirst("h3")?.let { lineHost(it).textLines() }.orEmpty()
        val title = cleanEventTitle(heading.firstOrNull().orEmpty())
        if (title.isBlank()) {
            logger.warn { "gART.n block '${block.selectFirst("h2")?.text()}' has no title, skipping" }
            return null
        }
        val eventDate = parseBlockDate(block)
        if (eventDate == null) {
            logger.warn { "No parseable date for gART.n event '$title', skipping" }
            return null
        }

        val paragraphs = block.select("p")
        val openingHours = paragraphs.firstOrNull { TIME_RANGE.matches(it.text().trim()) }
        val ticketNoteParagraph = paragraphs.firstOrNull { it.parent()?.selectFirst(TICKET_LIST) != null }
        val lineup = paragraphs.firstOrNull { it !== openingHours && it !== ticketNoteParagraph }
        val ticketLink = block.selectFirst("$TICKET_LIST a[href]")
        val ticketNote = ticketNoteParagraph?.text()?.trim()

        return ScrapedEvent(
            title = title,
            // The venue breaks a hosted night's heading after its series name ("SONNTAGS IM gART.n"
            // / "by LOTTE AHOI"); the tail names the host and reads as a subtitle.
            subtitle = heading.drop(1).joinToString(" ").ifBlank { null },
            // Every night here is a DJ party; the venue states no category and publishes no genre.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            // The page states opening hours ("14:00 - 22:00"), whose first half is when the party
            // starts. There is no separate doors time and no field for the closing time.
            startTime = parseTime(TIME_RANGE.find(openingHours?.text().orEmpty())?.groupValues?.get(1)),
            // No per-event page exists, so every night points at the programme and takes its
            // identity from the date plus the slugified title.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.GARTN.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = ticketLink?.attr("href")?.takeIf { it.isNotBlank() }?.let { resolveUrl(baseUrl, it) },
            priceNote = ticketNote?.takeIf { it.isNotBlank() },
            soldOut =
                SOLD_OUT_LABEL.containsMatchIn(ticketLink?.text().orEmpty()) &&
                    !DOOR_TICKETS_NOTE.containsMatchIn(ticketNote.orEmpty()),
            artists = parseLineup(lineup)
        )
    }

    /**
     * Reads the block's `SA 08.08.` heading, inferring the year from the stated weekday.
     *
     * Returns `null` when the heading names an impossible day/month (e.g. `31.02.`), which the
     * caller reports and skips.
     */
    private fun parseBlockDate(block: Element): LocalDate? {
        val (weekday, day, month) = (DATE_HEADING.find(dateHeading(block)) ?: return null).destructured
        return runCatching { MonthDay.of(month.toInt(), day.toInt()) }
            .getOrNull()
            ?.let { inferYearForWeekday(it, GERMAN_WEEKDAY_ABBREVIATIONS[weekday.lowercase()], clock) }
    }

    /** A block's `h2` heading text, which is a `SA 08.08.` date on every event block and nothing else. */
    private fun dateHeading(block: Element): String =
        block
            .selectFirst("h2")
            ?.text()
            ?.trim()
            .orEmpty()

    /**
     * Reads the night's DJs from its lineup paragraph, one act per `<br>`-separated line.
     *
     * A line is a single billing — the venue never packs two acts onto one line except via a `b2b`
     * marker, so no conjunction or comma splitting applies and an act whose own name contains `&`
     * ("Caleesi & Kreis") survives whole. A trailing `live` set-format marker is trimmed by
     * [stripArtistSuffix], an unannounced lineup ("TBA") is dropped by [isNonArtistName], and an act
     * billed twice on one night is deduplicated — two `event_artist` rows for the same
     * (event, artist) pair would hit that table's unique constraint and fail the whole import.
     */
    private fun parseLineup(paragraph: Element?): List<ScrapedArtist> {
        if (paragraph == null) return emptyList()
        return billingLines(paragraph)
            .flatMap { it.split(B2B_SEPARATOR) }
            .map { stripArtistSuffix(it.trim()) }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
            .distinctBy { it.lowercase() }
            .map { ScrapedArtist(name = it, role = "DJ") }
    }

    /**
     * Descends to the element that actually owns an element's `<br>`-separated lines.
     *
     * Carrd wraps a paragraph's content in a `<span class="p">` and a heading's in a
     * `<span class="p"><strong>`, and both [textLines][de.norm.events.scraper.textLines] and
     * [billingLines] break only on *direct-child* `<br>` elements — so without descending, a
     * two-line heading reads as one line. A wrapper is recognized structurally rather than by its
     * class: an only child holding the whole of its parent's text adds nesting, not content.
     */
    private tailrec fun lineHost(element: Element): Element {
        val wrapper = element.children().singleOrNull()?.takeIf { it.text() == element.text() } ?: return element
        return lineHost(wrapper)
    }

    /**
     * Splits a lineup paragraph into its `<br>`-separated billing lines, dropping any line built
     * only from `<sup>` annotations.
     *
     * This cannot use the shared [textLines][de.norm.events.scraper.textLines] helper because the
     * `<sup>` distinction is the whole point: an inline `<sup>` (`Amina <sup>b2b</sup> Luqqi`)
     * belongs to its line, whereas a line that is *nothing but* a `<sup>` annotates the line above
     * it rather than billing an act.
     */
    private fun billingLines(paragraph: Element): List<String> {
        val host = lineHost(paragraph)
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var isAnnotationOnly = true

        fun flush() {
            if (!isAnnotationOnly) lines.add(current.toString().trim())
            current.clear()
            isAnnotationOnly = true
        }

        for (node in host.childNodes()) {
            val text = if (node is TextNode) node.text() else (node as? Element)?.text().orEmpty()
            when {
                node is Element && node.tagName().equals("br", ignoreCase = true) -> {
                    flush()
                }

                node is Element && node.tagName().equals("sup", ignoreCase = true) -> {
                    current.append(' ').append(text)
                }

                else -> {
                    if (text.isNotBlank()) isAnnotationOnly = false
                    current.append(text)
                }
            }
        }
        flush()
        return lines.filter { it.isNotBlank() }
    }

    private companion object {
        /** An event block's date heading: a German weekday abbreviation and a `DD.MM.` date. */
        val DATE_HEADING = Regex("""(Mo|Di|Mi|Do|Fr|Sa|So)\s+(\d{1,2})\.(\d{1,2})\.?""", RegexOption.IGNORE_CASE)

        /** The opening-hours paragraph — a start time, optionally followed by the closing time. */
        val TIME_RANGE = Regex("""(\d{1,2}:\d{2})(?:\s*[-–—]\s*\d{1,2}:\d{2})?""")

        /** Carrd's button list, which holds the night's ticket link and, beside it, the ticket note. */
        const val TICKET_LIST = "ul.buttons-component"

        /** The ticket button's sold-out wording ("pre-sale sold out"). */
        val SOLD_OUT_LABEL = Regex("""sold\s*out|ausverkauft""", RegexOption.IGNORE_CASE)

        /** The note that qualifies a sold-out presale — the night still has tickets at the door. */
        val DOOR_TICKETS_NOTE = Regex("""at\s+the\s+door|abendkasse""", RegexOption.IGNORE_CASE)

        /** The back-to-back marker joining two DJs into one slot. */
        val B2B_SEPARATOR = Regex("""\s+b2b\s+""", RegexOption.IGNORE_CASE)

        /** German two-letter weekday abbreviations used in the date headings. */
        val GERMAN_WEEKDAY_ABBREVIATIONS: Map<String, DayOfWeek> =
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
