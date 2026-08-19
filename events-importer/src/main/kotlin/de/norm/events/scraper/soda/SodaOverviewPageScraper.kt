package de.norm.events.scraper.soda

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/**
 * Pure HTML parser for Soda Club Berlin's `/events` listing (overview) page.
 *
 * The page groups the upcoming programme under German month headings, rendering every
 * night as a `.event-snippet` card: the flyer (`.thumbnail img`), a title link to the
 * `/de/events/<slug>` detail page (`h4.title a`), an optional `#tickets` button, and a
 * three-part calendar block (`.event-date-cal-weekday` / `-day` / `-month`) holding the
 * German weekday, the day of month, and the **abbreviated** month — but no year.
 *
 * The overview is the discovery list; [SodaDetailPageScraper] is the primary source for
 * every field (it carries a schema.org `MusicEvent` block with the full start date).
 * Each card is nonetheless parsed as completely as the listing allows, because
 * [SodaWebsiteImporter] falls back to this data whenever a detail page fails to fetch.
 * The missing year is inferred from the stated weekday via [inferYearForWeekday] — the
 * same approach the retro single-page scrapers use, and more robust than reading the
 * date out of the slug, which the venue spells inconsistently (`…-15-08-2026` on most
 * events, `…-150826` on others).
 *
 * @see SodaDetailPageScraper for the primary per-event data source.
 * @see SodaWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.soda-berlin.de/events">Soda Club event listing</a>
 */
class SodaOverviewPageScraper(
    /** Clock for weekday-based year inference. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the overview page document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the relative
     *   detail links and build `sourceId` values.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val snippets = document.select(".event-snippet")
        logger.info { "Found ${snippets.size} event snippet(s) on overview page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the entire import
        return snippets.mapNotNull { snippet ->
            try {
                parseSnippet(snippet, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse event snippet, skipping" }
                null
            }
        }
    }

    /**
     * Parses a single `.event-snippet` card into a [ScrapedEvent], or `null` when it
     * carries no title link or no resolvable date (the two fields the listing must supply
     * for the event to stand on its own if its detail page is unavailable).
     */
    @Suppress("ReturnCount") // Guard clauses for the required link/title/date are clearer than nesting
    private fun parseSnippet(
        snippet: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val link = snippet.selectFirst("h4.title a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = link.text().trim().takeIf { it.isNotBlank() } ?: return null
        val eventDate = parseCalendarDate(snippet) ?: return null

        val sourceUrl = resolveUrl(baseUrl, href)
        return ScrapedEvent(
            title = title,
            // Soda is a discotheque: every listing is a resident club night, never a billed act.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            imageUrl = snippet.imgSrcAt(".thumbnail img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SODA.sourceIdPrefix}${sodaEventSlug(sourceUrl)}",
            ticketUrl = snippet.attrAt("a.ticket-btn", "href")?.let { resolveUrl(baseUrl, it) }
        )
    }

    /**
     * Assembles the date from the card's year-less calendar block — German weekday, day of
     * month, and abbreviated month — inferring the year from the weekday via
     * [inferYearForWeekday]. Returns `null` when the day or month cannot be read.
     */
    private fun parseCalendarDate(snippet: Element): LocalDate? {
        val day = snippet.textAt(".event-date-cal-day")?.toIntOrNull()
        val month = parseGermanMonthAbbreviation(snippet.textAt(".event-date-cal-month"))
        val weekday = GERMAN_WEEKDAYS[snippet.textAt(".event-date-cal-weekday")?.lowercase()]
        val monthDay = if (day == null || month == null) null else runCatching { MonthDay.of(month, day) }.getOrNull()
        return monthDay?.let { inferYearForWeekday(it, weekday, clock) }
    }

    private companion object {
        /** Full German weekday names as rendered in the calendar block ("Donnerstag"). */
        private val GERMAN_WEEKDAYS: Map<String, DayOfWeek> =
            mapOf(
                "montag" to DayOfWeek.MONDAY,
                "dienstag" to DayOfWeek.TUESDAY,
                "mittwoch" to DayOfWeek.WEDNESDAY,
                "donnerstag" to DayOfWeek.THURSDAY,
                "freitag" to DayOfWeek.FRIDAY,
                "samstag" to DayOfWeek.SATURDAY,
                "sonntag" to DayOfWeek.SUNDAY
            )
    }
}

/**
 * Extracts the event slug — the last path segment of a `/de/events/<slug>` detail URL —
 * used to build a stable [ScrapedEvent.sourceId].
 *
 * Read as the last segment rather than by stripping a fixed `/de/events/` prefix so the
 * same identity is produced no matter which language prefix the configured entry URL
 * leads to (`/de/events/…`, `/en/events/…`). The slug itself is the venue's stable key;
 * its embedded date is spelled inconsistently (`…-15-08-2026` vs `…-150826`) and is
 * therefore never parsed. Shared by the overview and detail scrapers.
 */
internal fun sodaEventSlug(url: String): String = URI(url).path.trimEnd('/').substringAfterLast('/')
