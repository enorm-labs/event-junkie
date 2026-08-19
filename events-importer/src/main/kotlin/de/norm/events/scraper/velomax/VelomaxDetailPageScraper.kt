package de.norm.events.scraper.velomax

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseSchemaEventStatus
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parser for a Velomax hall's `/events/event/<slug>` detail page.
 *
 * The page embeds the event as **schema.org Microdata** — one
 * `[itemtype=https://schema.org/Event]` block carrying `name`, `alternateName`, `performer`,
 * `eventStatus`, and `startDate` / `doorTime` as `<time datetime="2026-08-29 20:00:00">` — so the
 * structured data is read rather than the rendered markup (ADR-007 §"Selector Strategy" priority
 * 1). Only the description, the ticket link and the poster come from the surrounding HTML.
 *
 * Everything is scoped to that Microdata block, which matters because the page also renders a
 * `section.additional-content` of teasers for *other* events, each with its own date, title and
 * image; reading the page unscoped would mix a neighbouring show's data into this one.
 *
 * @see VelomaxOverviewPageScraper for the shared listing (discovery, hall filter, fallback).
 * @see <a href="https://www.velodrom.de/events/event/joji-velodrom-2026-08-29">Example detail page</a>
 */
class VelomaxDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page carries no
     * schema.org `Event` block or no name within it.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive its
     *   [ScrapedEvent.sourceId].
     * @param hall the hall this source imports, supplying the `sourceId` prefix.
     */
    @Suppress("ReturnCount") // Guard clauses for the missing Microdata block / name are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String,
        hall: VelomaxHall
    ): ScrapedEvent? {
        val event = document.selectFirst("[itemtype='https://schema.org/Event']")
        if (event == null) {
            logger.warn { "Detail page at $sourceUrl has no schema.org Event block, skipping" }
            return null
        }
        val title = event.textAt("[itemprop=name]")?.let(::cleanEventTitle)
        if (title == null) {
            logger.warn { "Detail page at $sourceUrl has no event name, skipping" }
            return null
        }

        val slug = extractEventSlug(sourceUrl, EVENT_PATH_PREFIX)
        val subtitle = event.textAt("[itemprop=alternateName]")
        val startedAt = event.attrAtProp("startDate", "datetime")
        val eventType = inferConcertVenueType(title)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            // Scoped to the single-event block: the page's teaser strip carries other events' prose.
            description = document.textAt(".eventSingle .event-content"),
            eventType = eventType,
            // `datetime` is a machine-readable "yyyy-MM-dd HH:mm:ss"; the rendered text is only "20:00 Uhr".
            eventDate = startedAt?.let { parseIsoDate(it.substringBefore(' ')) } ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(event.attrAtProp("doorTime", "datetime")?.clockTime()),
            startTime = parseTime(startedAt?.clockTime()),
            imageUrl = parseImageUrl(document, sourceUrl),
            sourceUrl = sourceUrl,
            // Show-level, and deliberately so: this permalink is one page per production, which is
            // exactly why a same-day run of sessions cannot be keyed from here. The importer keeps
            // the listing's session-keyed id instead (see
            // `AbstractVelomaxHallImporter.fillGapsFromOverview`); this one only stands in when the
            // page is parsed on its own.
            sourceId = "${hall.eventSource.sourceIdPrefix}$slug",
            ticketUrl = parseTicketUrl(document),
            status = parseSchemaEventStatus(event.attrAtProp("eventStatus", "content")),
            artists = buildArtistsForEventType(title, subtitle, eventType),
            promoters = listOfNotNull(event.textAt("[itemprop=organizer] [itemprop=name]"))
        )
    }

    /**
     * The event's own poster, taken from the page's stage banner.
     *
     * Scoped to `section.stage` on purpose: the teaser strip further down carries the *next*
     * events' images, and the first `<img>` on the page would otherwise belong to whichever of
     * those the theme rendered first. Paths are site-relative (`/fileadmin/…`).
     */
    private fun parseImageUrl(
        document: Document,
        sourceUrl: String
    ): String? {
        val src =
            document
                .selectFirst("section.stage img[src]")
                ?.attr("src")
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { resolveUrl(sourceUrl, src) }.getOrNull()
    }

    /** The external ticket-shop link, rendered in the performance block beside the times. */
    private fun parseTicketUrl(document: Document): String? =
        document
            .select(".eventSingle a[href]")
            .firstOrNull { TICKET_HOST.containsMatchIn(it.attr("href")) }
            ?.attr("href")
            ?.takeIf { it.startsWith("http") }

    private companion object {
        /** Path prefix of a hall's event permalink, stripped to obtain the slug identity. */
        const val EVENT_PATH_PREFIX = "/events/event/"

        /** Ticket-shop hosts the halls link to. */
        val TICKET_HOST = Regex("""eventim\.|ticketmaster\.|reservix\.|tickets\.""", RegexOption.IGNORE_CASE)
    }
}

/** Reads [attribute] off the first descendant carrying `itemprop="[prop]"`, or `null`. */
private fun Element.attrAtProp(
    prop: String,
    attribute: String
): String? =
    selectFirst("[itemprop=$prop]")
        ?.attr(attribute)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

/** The `HH:mm` clock part of a Microdata `yyyy-MM-dd HH:mm:ss` timestamp. */
private fun String.clockTime(): String? = substringAfter(' ', "").takeIf { it.isNotBlank() }?.take(HH_MM_LENGTH)

/** Length of an `HH:mm` prefix. */
private const val HH_MM_LENGTH = 5
