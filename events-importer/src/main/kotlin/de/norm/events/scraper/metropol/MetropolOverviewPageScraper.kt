package de.norm.events.scraper.metropol

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ISO_DATE_LENGTH
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.stripRelocationPrefix
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Metropol Berlin's Events-Manager `/events/` listing (overview) page.
 *
 * The page carries the venue's whole programme unpaginated as `li.event` rows. Each row has a
 * `.date` block — a `.day` (`04/`), a `.monthyear` (`Aug. 2026`), a `.time` whose own text is
 * the start time and whose nested `<small>` holds `Einlass: HH:mm` — a category link
 * (`Konzert` / `Party`), an `h2.artist` title whose `small.support` child lists the `+`-joined
 * support acts, and a link to the `/event/<iso-date-slug>` detail page.
 *
 * The overview is the discovery list plus the fallback for every field the detail page also
 * carries (the importer degrades to it when a detail fetch fails), and the **only** source for
 * the support acts — the detail page's `h1` names the headliner alone.
 *
 * @see MetropolDetailPageScraper for the detail-page data (promoter, subtitle, image, ticket, text).
 * @see MetropolWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://metropol-berlin.de/events">Metropol event listing</a>
 */
class MetropolOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event rows from the overview page document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the per-event
     *   detail links and build `sourceId` values.
     * @return a list of [ScrapedEvent] instances, one per listed row.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("ul.eventlist li.event")
        logger.info { "Found ${rows.size} event row(s) on Metropol overview" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed rows without aborting the import
        return rows.mapNotNull { row ->
            try {
                parseRow(row, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Metropol event row, skipping" }
                null
            }
        }
    }

    /** Parses a single `li.event` row into a [ScrapedEvent], or `null` when it has no link or title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseRow(
        row: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = row.selectFirst("a.title[href]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, "/event/")

        // The title's support acts live in a nested <small>, so read the heading's own text
        // rather than .text(), which would glue "Thy Art is MurderFit For An Autopsy + …".
        val rawTitle =
            row
                .selectFirst("h2.artist")
                ?.ownText()
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return null
        val title = cleanEventTitle(stripRelocationPrefix(rawTitle))
        val support = row.textAt("h2.artist small.support")
        val eventType = mapEventType(row.textAt("ul.event-categories"))

        return ScrapedEvent(
            title = title,
            subtitle = support,
            eventType = eventType,
            // The slug's ISO prefix is the canonical date; the rendered German block is the fallback.
            eventDate =
                parseIsoDate(slug.take(ISO_DATE_LENGTH))
                    ?: parseRenderedDate(row)
                    ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseMetropolTime(row.textAt(".date .time small")),
            // The .time element's own text is the start; its <small> child is the doors line.
            startTime = parseMetropolTime(row.selectFirst(".date .time")?.ownText()),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.METROPOL.sourceIdPrefix}$slug",
            // Cancellation is the `.attention` badge; a relocation *away* from the house is the
            // title prefix. The neighbouring `.changes` prose is deliberately not read — see
            // MetropolDetailPageScraper.parseStatus.
            status = parseEventStatus("${row.textAt(".info .attention").orEmpty()} $rawTitle"),
            artists = buildMetropolArtists(title, support, eventType)
        )
    }

    /**
     * Parses the rendered German date from the row's calendar block — a `.day` carrying the day
     * with a trailing slash (`04/`) and a `.monthyear` carrying an abbreviated German month and
     * the year (`Aug. 2026`, `März 2027`). Returns `null` when any part is missing or unparseable.
     */
    private fun parseRenderedDate(row: Element): LocalDate? {
        val day = row.textAt(".date .day")?.trim('/', ' ')?.toIntOrNull()
        val monthYear = row.textAt(".date .monthyear")?.split(WHITESPACE).orEmpty()
        val month = parseGermanMonthAbbreviation(monthYear.firstOrNull())
        val year = monthYear.lastOrNull()?.toIntOrNull()
        return if (day == null || month == null || year == null) {
            null
        } else {
            runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
    }
}

/**
 * Builds the artist roster for a Metropol event: the headliner(s) from the [title], then the
 * acts on the listing's `small.support` line, in billing order.
 *
 * The shared [buildArtistsForEventType] cannot be used directly because it reads support acts
 * via `extractSupportFromSubtitle`, which requires an explicit `"Support:"` marker. Metropol
 * writes a bare `+`-joined list (`"Fit For An Autopsy + Sun Eater + Protest The Hero"`), so the
 * line is handed straight to [splitSupportActs]. A `PARTY` title is an event name rather than an
 * act, so it yields no artists — the same rule [buildArtistsForEventType] applies.
 */
internal fun buildMetropolArtists(
    title: String,
    supportLine: String?,
    eventType: String?
): List<ScrapedArtist> {
    if (eventType == EventType.PARTY.name) return emptyList()
    val supportActs =
        supportLine
            .orEmpty()
            .let(::splitSupportActs)
            .filterNot { isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "SUPPORT") }
    return headlinersFromTitle(title) + supportActs
}

/**
 * Parses an `HH:mm` time out of a Metropol time fragment, which may carry a leading label
 * (`Einlass: 19:00`, `Beginn: 20:00`) or be the bare time the overview's `.time` renders.
 *
 * Returns `null` for a **midnight** value: the venue writes an unset start time as `0:00`
 * (e.g. `Einlass: 18:00 // Beginn: 0:00`), and storing that as `00:00` would not merely be
 * wrong — the shared `orderDoorsBeforeStart` guard at the persistence boundary would then read
 * doors 18:00 as "later than the start" and swap the two, inventing an 18:00 start. No Metropol
 * show genuinely begins at midnight; the hall's latest listed start is 20:30.
 */
internal fun parseMetropolTime(text: String?): LocalTime? =
    TIME_PATTERN.find(text.orEmpty())?.let { match ->
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour == 0 && minute == 0) null else runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

/**
 * Matches an `H:mm` / `HH:mm` time anywhere in a fragment, tolerating the single-digit hour the
 * venue writes for its `0:00` placeholder (which the shared `HH:mm` formatter would reject).
 */
private val TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})""")
