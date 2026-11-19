package de.norm.events.scraper.hole44

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ISO_DATE_LENGTH
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseListing
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Hole 44 Berlin's Events-Manager listing (overview) page.
 *
 * `/events/` renders every upcoming show as an `li.event-item`: an `.event_date` column (day /
 * month-year / start time), an `a.artist` title link to the `/event/<date-slug>/` page, an
 * inline `.event-support` span, an `.event-tags` genre list, and for relocated/cancelled shows
 * a leading `.changes` note.
 *
 * The overview is the discovery list plus every field except the detail-only ones (promoter,
 * doors time, image, description). [Hole44WebsiteImporter] falls back to it when a detail page
 * fails, so each event is parsed as completely as the listing allows. The date is read from
 * the ISO `YYYY-MM-DD` prefix in every event slug — cleaner than the German `"Juli 2026"`
 * month rendering.
 *
 * @see Hole44DetailPageScraper for the detail-page data source (promoter, doors, image).
 * @see Hole44WebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://hole-berlin.de/events/">Hole 44 event listing</a>
 */
class Hole44OverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event items from the overview page.
     *
     * @param baseUrl the URL the document was fetched from, for detail links and `sourceId` values.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("li.event-item")
        return items.parseListing(logger, "event item") { item ->
            parseItem(item, baseUrl)
        }
    }

    /** Parses one `li.event-item` into a [ScrapedEvent], or `null` without a title link. */
    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val link = item.selectFirst("a.artist") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, "/event/")

        // The anchor text is the headliner; a nested `.event-support` span holds the support line.
        val title = link.ownText().blankToNull() ?: return null
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
            statusNote = item.textAt("span.changes"),
            artists = hole44Artists(title, support, eventType)
        )
    }
}

/**
 * The genre labels from a `.event-tags` block as one comma-separated string ("crossover trash,
 * Hardcore-Punk, Trash Metal"), or `null` without tags. Shared by both scrapers, whose
 * `.event-tags` markup is identical.
 */
internal fun parseGenres(root: Element): String? =
    root
        .select(".event-tags a")
        .map { it.text().trim() }
        .filter { it.isNotBlank() }
        .joinToString(", ")
        .takeIf { it.isNotBlank() }

/**
 * The acts of a Hole 44 title. A title joined by ` & ` names a tour when one part holds a tour
 * word or is a place, as in `H1GHR SEASON IN EUROPE & UK` (#5195): the title then bills no act,
 * and only the support line does. `Caramella & YDRA` and `DONNA SAVAGE & BRENK SINATRA` stay acts.
 */
internal fun hole44Artists(
    title: String,
    support: String?,
    eventType: String?,
    description: String? = null
): List<ScrapedArtist> {
    val artists = buildArtistsForEventType(title, support, eventType, description)
    return if (isTourTitle(title)) artists.filterNot { it.titleDerived } else artists
}

private val TOUR_WORD = Regex("""\b(?:season|tour|showcase)\b""", RegexOption.IGNORE_CASE)
private val PLACE_PARTS = setOf("uk", "europe")

private fun isTourTitle(title: String): Boolean {
    val parts = title.split('&').map { it.trim() }
    return parts.size > 1 && parts.any { TOUR_WORD.containsMatchIn(it) || it.lowercase() in PLACE_PARTS }
}
