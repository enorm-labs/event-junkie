package de.norm.events.scraper.soda

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseGermanWeekday
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.time.MonthDay

/**
 * Pure HTML parser for Soda Club Berlin's `/events` listing (overview) page.
 *
 * Upcoming nights sit under German month headings as `.event-snippet` cards: flyer
 * (`.thumbnail img`), title link to `/de/events/<slug>` (`h4.title a`), optional `#tickets`
 * button, and a calendar block (`.event-date-cal-weekday` / `-day` / `-month`) with German
 * weekday, day of month and **abbreviated** month — no year.
 *
 * The overview is the discovery list; [SodaDetailPageScraper] is primary for every field (its
 * schema.org `MusicEvent` block has the full date). Each card is still parsed as completely as
 * possible because [SodaWebsiteImporter] falls back to it when a detail page fails. The year is
 * inferred from the weekday via [inferYearForWeekday] — as the retro single-page scrapers do,
 * and more robust than the slug, which the venue spells inconsistently (`…-15-08-2026` on most
 * events, `…-150826` on others).
 *
 * @see SodaDetailPageScraper for the primary per-event data source.
 * @see SodaWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.soda-berlin.de/events">Soda Club event listing</a>
 */
class SodaOverviewPageScraper(
    /** Clock for weekday-based year inference; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the overview page.
     *
     * @param baseUrl the URL the document was fetched from, for detail links and `sourceId` values.
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
     * Parses one `.event-snippet` card into a [ScrapedEvent], or `null` without a title link or
     * resolvable date — the two fields the listing must supply for the event to stand alone
     * without its detail page.
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
     * The date from the year-less calendar block — German weekday, day, abbreviated month — with
     * the year from the weekday via [inferYearForWeekday]. `null` when day or month is unreadable.
     */
    private fun parseCalendarDate(snippet: Element): LocalDate? {
        val day = snippet.textAt(".event-date-cal-day")?.toIntOrNull()
        val month = parseGermanMonthAbbreviation(snippet.textAt(".event-date-cal-month"))
        val weekday = parseGermanWeekday(snippet.textAt(".event-date-cal-weekday"))
        val monthDay = if (day == null || month == null) null else runCatching { MonthDay.of(month, day) }.getOrNull()
        return monthDay?.let { inferYearForWeekday(it, weekday, clock) }
    }
}

/**
 * The event slug — last path segment of a `/de/events/<slug>` URL — for a stable
 * [ScrapedEvent.sourceId]. Last segment rather than a stripped `/de/events/` prefix, so the
 * identity is the same under any language prefix (`/de/events/…`, `/en/events/…`). The slug is
 * the venue's stable key; its embedded date is spelled inconsistently (`…-15-08-2026` vs
 * `…-150826`) and never parsed. Shared by both scrapers.
 */
internal fun sodaEventSlug(url: String): String = URI(url).path.trimEnd('/').substringAfterLast('/')
