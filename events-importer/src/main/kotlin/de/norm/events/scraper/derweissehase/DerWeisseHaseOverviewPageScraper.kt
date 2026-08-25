package de.norm.events.scraper.derweissehase

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.derweissehase.DerWeisseHaseOverviewPageScraper.Companion.LINEUP_SEPARATOR
import de.norm.events.scraper.derweissehase.DerWeisseHaseOverviewPageScraper.Companion.LINE_UP_HEADING
import de.norm.events.scraper.derweissehase.DerWeisseHaseOverviewPageScraper.Companion.RA_EVENT_URL
import de.norm.events.scraper.derweissehase.DerWeisseHaseOverviewPageScraper.Companion.UNANNOUNCED_SLOT_PATTERN
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Der Weiße Hase's Contao event listing.
 *
 * Every upcoming night is one `.event50` block wrapping a single `a.eventlistlink` — the club sells
 * through RA and has no per-event page, so the anchor points off-site and the night renders inline:
 *
 * ```
 * <p class="dater">Donnerstag 06.08.2026 23:00</p>   ← weekday, dotted date, start time
 * <h1>straff / thursday techno</h1>                   ← the night's name
 * <p class="text">                                    ← optional note, then the roster
 *   <p>free entry until midnight*</p>
 *   <h4>LINE UP</h4>
 *   <p>Fran-Cee, Fabian Fischbach, DAV3 + Surprise DJ</p>
 * ```
 *
 * **`p.text` is not a usable container.** The CMS nests `<h4>` and `<p>` inside a `<p>`, which no HTML
 * parser accepts: Jsoup closes `p.text` at the first `<h4>`, leaving it empty and hoisting the note,
 * heading and roster to be siblings of `h1` inside `.eventrahm`. This parser walks `.eventrahm`'s
 * children as one ordered stream and keys off content — the roster is the element after the
 * [LINE_UP_HEADING] heading, and everything between the title and it is the night's note.
 *
 * The roster splits on commas, `+` and `<br>` only, never on `&` ([LINEUP_SEPARATOR]): the club writes
 * back-to-back billings as separate entries but uses `&` *inside* act names ("Drauf & Dran DJ Team").
 * An unbooked slot ("+ Residents") is dropped on a fully anchored match
 * ([UNANNOUNCED_SLOT_PATTERN]), so a real act is never caught, and only a Resident Advisor *event*
 * link becomes the ticket URL ([RA_EVENT_URL]) — a night whose RA page is not up links to the club's
 * profile or a bare `#`. A note line is stored as the description rather than as a `priceNote`, which
 * would trip `detectFree` and flag a paid night free for its whole run when entry is free for the
 * first hour only. Every act carries the `DJ` role, the club billing no headliner.
 *
 * @see DER_WEISSE_HASE_LIMITATIONS for what the club does not publish.
 * @see DerWeisseHaseWebsiteImporter for the HTTP fetch orchestrator.
 */
@Suppress("LongComment") // 8 of these lines are the malformed markup the ordered-stream walk exists to survive.
class DerWeisseHaseOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all announced nights from the event listing document.
     *
     * @param baseUrl the URL the document was fetched from, stored as each event's
     *   [ScrapedEvent.sourceUrl] — the venue publishes no per-event page.
     * @return a list of [ScrapedEvent] instances, in listing order.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val blocks = document.select("div.event50:has(.eventrahm)")
        logger.info { "Found ${blocks.size} event block(s) on Der Weiße Hase listing" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed blocks without aborting the whole import
        return blocks.mapNotNull { block ->
            try {
                parseBlock(block, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Der Weiße Hase event block, skipping" }
                null
            }
        }
    }

    /** Parses one `.event50` block into a [ScrapedEvent], or `null` when it has no title or usable date. */
    @Suppress("ReturnCount") // Guard clauses for the required title/date are clearer than nesting
    private fun parseBlock(
        block: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val content = block.selectFirst(".eventrahm") ?: return null
        val title = content.textAt("h1")?.let(::cleanEventTitle)
        if (title.isNullOrBlank()) {
            logger.warn { "No title in Der Weiße Hase event block, skipping" }
            return null
        }

        val dateLine = content.textAt("p.dater").orEmpty()
        val eventDate = parseDateLine(dateLine)
        if (eventDate == null) {
            logger.warn { "Unparseable Der Weiße Hase date line '$dateLine' for '$title', skipping" }
            return null
        }

        val lineupHeading = content.children().firstOrNull { isLineUpHeading(it) }
        return ScrapedEvent(
            title = title,
            description = noteBefore(content, lineupHeading),
            // The club programmes nothing but DJ nights and states no category anywhere, so the
            // type is fixed rather than inferred from the night's name.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = parseStartTime(dateLine),
            // The flyer is served from a root-relative path, so it needs resolving against the listing URL.
            imageUrl = block.attrAt(".stpic img", "src")?.let { runCatching { resolveUrl(baseUrl, it) }.getOrNull() },
            // No per-event page exists, so every night points at the listing and takes its identity
            // from the date plus the slugified title. Both are needed: a recurring night reuses its
            // title across weeks, and two different nights can share one date (a daytime rave and
            // its evening aftershow).
            sourceUrl = baseUrl,
            sourceId = "${EventSource.DER_WEISSE_HASE.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = block.selectFirst("a.eventlistlink")?.absUrl("href")?.takeIf { RA_EVENT_URL.matches(it) },
            artists = parseLineup(lineupHeading?.nextElementSibling())
        )
    }

    /** The date part of a `"Donnerstag 06.08.2026 23:00"` line; the weekday is redundant given the full year. */
    private fun parseDateLine(dateLine: String): LocalDate? = parseGermanDate(DATE_LINE_PATTERN.find(dateLine)?.groupValues?.get(1))

    /** The time part of a `"Donnerstag 06.08.2026 23:00"` line — when the doors open and the night starts. */
    private fun parseStartTime(dateLine: String): LocalTime? = parseTime(DATE_LINE_PATTERN.find(dateLine)?.groupValues?.get(2))

    /** True when [element] is the `LINE UP` heading that introduces the roster. */
    private fun isLineUpHeading(element: Element): Boolean = LINE_UP_HEADING.matches(element.text().trim())

    /**
     * The night's note — the club's own lines between the title and the `LINE UP` heading, joined
     * by a newline, or `null` when there are none.
     *
     * The club writes these as either a `<p>` ("free entry until midnight*") or another `<h4>`
     * ("Women & FLINTA free until 1 AM"), so the note is taken by position rather than by tag. The
     * `&nbsp;` spacer paragraph the CMS emits after every title reads as blank and is dropped.
     */
    private fun noteBefore(
        content: Element,
        lineupHeading: Element?
    ): String? {
        val children = content.children()
        val start = children.indexOfFirst { it.tagName() == "h1" }
        val end = lineupHeading?.let { children.indexOf(it) } ?: children.size
        if (start < 0 || end <= start) return null
        return children
            .subList(start + 1, end)
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    /**
     * The DJs billed for the night, in listing order.
     *
     * The roster is one element whose names are separated by `<br>` or by [LINEUP_SEPARATOR]
     * punctuation. Placeholders ([isNonArtistName]) and unbooked slots ([UNANNOUNCED_SLOT_PATTERN])
     * are dropped rather than minted as artists.
     */
    private fun parseLineup(lineup: Element?): List<ScrapedArtist> =
        lineup
            ?.takeUnless { isLineUpHeading(it) }
            ?.textLines()
            .orEmpty()
            .flatMap { it.split(LINEUP_SEPARATOR) }
            .map { it.trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) && !isUnannouncedSlot(it) }
            .map { ScrapedArtist(name = it, role = "DJ") }

    /** True when [name] is an unbooked billing slot rather than a performer — see [UNANNOUNCED_SLOT_PATTERN]. */
    private fun isUnannouncedSlot(name: String): Boolean = UNANNOUNCED_SLOT_PATTERN.matches(name.trim().replace(WHITESPACE_RUN, " "))

    private companion object {
        /**
         * A Der Weiße Hase date line — `"Donnerstag 06.08.2026 23:00"`. Captures the dotted date
         * (group 1) and the time (group 2). Anchored at the weekday so a line that merely mentions a
         * date in prose cannot be read as one.
         */
        private val DATE_LINE_PATTERN = Regex("""^\s*\p{L}+\s+(\d{1,2}\.\d{1,2}\.\d{4})(?:\s+(\d{1,2}:\d{2}))?""")

        /** The heading the club puts above every roster; matched whole and case-insensitively so `Line Up` works too. */
        private val LINE_UP_HEADING = Regex("""line\s*-?\s*up:?""", RegexOption.IGNORE_CASE)

        /**
         * Separators inside a roster line: comma and `+`. Deliberately **not** `&` — the club writes
         * act names that contain one ("Drauf & Dran DJ Team") — and deliberately not `/`, which it
         * has never used to separate two DJs.
         */
        private val LINEUP_SEPARATOR = Regex("""\s*[,+]\s*""")

        /**
         * An unbooked billing slot the club prints in place of a name: its own residents
         * ("Residents", "Resident DJs"), an unannounced guest ("Surprise DJ", "surprise Act") or a
         * yet-undecided DJ-contest slot ("Contest Winner"). Anchored, so a real act whose name merely
         * contains one of these words — including the band The Residents — is untouched.
         */
        private val UNANNOUNCED_SLOT_PATTERN =
            Regex("""residents?(?:\s+djs?)?|surprise\s+(?:dj|act|guest)s?|contest\s+winners?""", RegexOption.IGNORE_CASE)

        /**
         * A Resident Advisor **event** page. The club links every night's tickets there; a night whose
         * RA page is not published yet links to the club's RA profile (`ra.co/clubs/…`) or to a bare
         * `#` on the listing itself, and neither is a ticket URL.
         */
        private val RA_EVENT_URL = Regex("""https?://(?:[\w-]+\.)?ra\.co/events/\d+/?""", RegexOption.IGNORE_CASE)

        /** A run of whitespace inside a roster entry, collapsed before the anchored slot match. */
        private val WHITESPACE_RUN = Regex("""\s+""")
    }
}
