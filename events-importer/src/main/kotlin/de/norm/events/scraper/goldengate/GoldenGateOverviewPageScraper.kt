package de.norm.events.scraper.goldengate

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.goldengate.GoldenGateOverviewPageScraper.Companion.DATE_LINE_PATTERN
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.textLines
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure HTML parser for Golden Gate Berlin's homepage programme.
 *
 * The club announces only the **current Thursday–Saturday block** — three nights at a time, each
 * rendered as an Elementor container holding exactly three headings:
 *
 * ```
 * Do. 30. Juli 2026 - 23:59      ← date line: weekday, day, German month, year, door time
 * Donnerdogge                     ← the night's name
 * Neco<br>Jeremy Reinhard<br>…    ← the DJ roster
 * ```
 *
 * Elementor names every element with a per-element hash (`elementor-element-7b3297a`) that changes
 * whenever the page is edited, and the theme adds no venue-semantic classes, so there is nothing
 * stable to anchor a container selector on. This parser therefore walks the
 * `.elementor-heading-title` headings — Elementor's own *widget-type* class, which is stable across
 * edits — as one ordered stream and keys off **content**: a heading matching [DATE_LINE_PATTERN]
 * opens a night, the next two headings are its title and lineup, and the next date heading closes
 * it. That also skips the page's trailing non-event headings ("Tickets only available at the
 * door.", "enter", "SHOPPING") without having to enumerate them.
 *
 * Nights that have already passed stay on the page until the block rolls over; they are parsed here
 * and dropped centrally at persistence time by
 * [EventUpsertService][de.norm.events.scraper.EventUpsertService], so an import late in the week
 * legitimately stores as little as one event.
 *
 * @see GoldenGateWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://goldengate-berlin.de/">Golden Gate Berlin</a>
 */
class GoldenGateOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the announced nights from the homepage document.
     *
     * @param baseUrl the URL the document was fetched from, stored as each event's
     *   [ScrapedEvent.sourceUrl] — the venue publishes no per-event page.
     * @return a list of [ScrapedEvent] instances, in listing order.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val headings = document.select(".elementor-heading-title")
        val events =
            headings
                .withIndex()
                .filter { (_, heading) -> DATE_LINE_PATTERN.containsMatchIn(heading.text()) }
                .mapNotNull { (index, heading) -> parseNight(heading, headings.getOrNull(index + 1), headings.getOrNull(index + 2), baseUrl) }
        logger.info { "Scraped ${events.size} night(s) from Golden Gate homepage" }
        return events
    }

    /**
     * Builds one night from its date heading and the two headings that follow it.
     *
     * [titleHeading] and [lineupHeading] are only used when they are *not* themselves a date line —
     * a night announced without a lineup (or as the last heading on the page) simply yields no
     * artists rather than absorbing the next night's date.
     */
    @Suppress("ReturnCount") // Guard clauses for the unparseable date / missing title are clearer than nesting
    private fun parseNight(
        dateHeading: Element,
        titleHeading: Element?,
        lineupHeading: Element?,
        baseUrl: String
    ): ScrapedEvent? {
        val dateLine = dateHeading.text()
        val match = DATE_LINE_PATTERN.find(dateLine) ?: return null
        val eventDate = parseGermanDate(match.groupValues[1])
        if (eventDate == null) {
            logger.warn { "Unparseable Golden Gate date line '$dateLine', skipping night" }
            return null
        }

        val title = nonDateHeading(titleHeading)?.text()?.trim()?.takeIf { it.isNotBlank() }
        if (title == null) {
            logger.warn { "No title heading after Golden Gate date line '$dateLine', skipping night" }
            return null
        }

        return ScrapedEvent(
            title = title,
            // Golden Gate is a techno club whose whole programme is DJ nights and which emits no
            // category at all, so the type is fixed rather than inferred from the night's name.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = parseTime(match.groupValues[2].takeIf { it.isNotBlank() }),
            // No per-event page exists, so every night points at the homepage and takes its identity
            // from the date plus the slugified title.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.GOLDEN_GATE.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            artists = parseLineup(nonDateHeading(lineupHeading))
        )
    }

    /** The heading itself, or `null` when it is the *next* night's date line rather than this night's content. */
    private fun nonDateHeading(heading: Element?): Element? = heading?.takeUnless { DATE_LINE_PATTERN.containsMatchIn(it.text()) }

    /**
     * The DJs billed for the night, in listing order.
     *
     * The roster is one heading with the names `<br>`-separated. A line is split further at safe
     * `&`/`and`/`und` boundaries ([splitSegmentOnConjunctions]), so a back-to-back billing
     * ("Nyna Curtis & Kisling") becomes two DJs while a name whose own spelling contains a
     * conjunction tail is left whole. Placeholder and non-artist entries are dropped rather than
     * minted as artists.
     */
    private fun parseLineup(lineupHeading: Element?): List<ScrapedArtist> =
        lineupHeading
            ?.textLines()
            .orEmpty()
            .flatMap(::splitSegmentOnConjunctions)
            .map { it.trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "DJ") }

    private companion object {
        /**
         * A Golden Gate date heading — `"Do. 30. Juli 2026 - 23:59"`. Captures the date without its
         * weekday (group 1, the only part that needs parsing — the weekday is redundant given the
         * full year) and the optional door time (group 2). Anchored at the start so a heading that
         * merely mentions a date in prose cannot open a night.
         */
        private val DATE_LINE_PATTERN =
            Regex("""^\s*\p{L}{2,3}\.?\s+(\d{1,2}\.\s+\p{L}+\s+\d{4})(?:\s*[-–—]\s*(\d{1,2}:\d{2}))?""")

        /** The date format inside a heading, e.g. "30. Juli 2026". */
        private val GERMAN_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN)

        /** Parses the German day/month/year part of a date heading, or `null` when it is not one. */
        private fun parseGermanDate(text: String): LocalDate? =
            try {
                LocalDate.parse(text.trim(), GERMAN_DATE_FORMATTER)
            } catch (_: DateTimeParseException) {
                null
            }
    }
}
