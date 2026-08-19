package de.norm.events.scraper.hole44

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Hole 44 Berlin's Events-Manager listing (overview) page.
 *
 * The `/events/` page renders every upcoming show as an `li.event-item` block: a
 * `.event_date` column (day / month-year / start time), an `a.artist` title link
 * to the `/event/<date-slug>/` detail page, an inline `.event-support` span, a
 * `.event-tags` genre list, and — for relocated/cancelled shows — a leading
 * `.changes` note.
 *
 * The overview is the source for the event discovery list plus every field except
 * the ones only the detail page carries (promoter, doors time, image, description).
 * Because [Hole44WebsiteImporter] falls back to this data when a detail page fails
 * to fetch, each event is parsed as completely as the listing allows. The date is
 * read from the ISO `YYYY-MM-DD` prefix the venue bakes into every event slug — a
 * canonical identifier that is cleaner than the German `"Juli 2026"` month rendering.
 *
 * @see Hole44DetailPageScraper for the detail-page data source (promoter, doors, image).
 * @see Hole44WebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://hole-berlin.de/events/">Hole 44 event listing</a>
 */
class Hole44OverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event items from the overview page document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve relative
     *   detail links and build `sourceId` values.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("li.event-item")
        logger.info { "Found ${items.size} event item(s) on overview page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the entire import
        return items.mapNotNull { item ->
            try {
                parseItem(item, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse event item, skipping" }
                null
            }
        }
    }

    /** Parses a single `li.event-item` block into a [ScrapedEvent], or `null` if it has no title link. */
    @Suppress("ReturnCount") // Guard clauses for the required link/href/title are clearer than nesting
    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val link = item.selectFirst("a.artist") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, "/event/")

        // The anchor text is the headliner; a nested `.event-support` span holds the support line.
        val title = link.ownText().trim().takeIf { it.isNotBlank() } ?: return null
        val support = item.textAt(".event-support")

        val eventType = inferConcertVenueType(title)
        return ScrapedEvent(
            title = title,
            subtitle = support,
            eventType = eventType,
            // Every slug is prefixed with the ISO event date; the sentinel is purely defensive.
            eventDate = parseIsoDate(slug.take(ISO_DATE_LENGTH)) ?: UNRESOLVED_EVENT_DATE,
            startTime = parseTime(item.textAt(".event_start")),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.HOLE44.sourceIdPrefix}$slug",
            genre = parseGenres(item),
            status = parseEventStatus(item.textAt("span.changes").orEmpty()),
            artists = buildArtistsForEventType(title, support, eventType)
        )
    }
}

/** Length of the leading ISO `YYYY-MM-DD` date the venue bakes into every event slug. */
internal const val ISO_DATE_LENGTH = 10

/**
 * Joins the genre labels from a `.event-tags` block into a single comma-separated
 * string (e.g. "crossover trash, Hardcore-Punk, Trash Metal"), or `null` when the
 * event carries no tags. Shared by the overview and detail scrapers, whose
 * `.event-tags` markup is identical.
 */
internal fun parseGenres(root: Element): String? =
    root
        .select(".event-tags a")
        .map { it.text().trim() }
        .filter { it.isNotBlank() }
        .joinToString(", ")
        .takeIf { it.isNotBlank() }
