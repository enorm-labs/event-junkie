package de.norm.events.scraper.velomax

import de.norm.events.scraper.HH_MM_LENGTH
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
import de.norm.events.scraper.textLinesAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parser for a Velomax hall's `/events/event/<slug>` detail page.
 *
 * The event is **schema.org Microdata** — one `[itemtype=https://schema.org/Event]` block with
 * `name`, `alternateName`, `performer`, `eventStatus`, and `startDate` / `doorTime` as
 * `<time datetime="2026-08-29 20:00:00">` — so the structured data is read, not the rendered
 * markup (ADR-007 §"Selector Strategy" priority 1). Only description, ticket link and poster
 * come from the surrounding HTML.
 *
 * Everything is scoped to that block: the page also renders a `section.additional-content` of
 * teasers for *other* events, each with its own date, title and image, and an unscoped read
 * would mix a neighbouring show in.
 *
 * @see VelomaxOverviewPageScraper for the shared listing (discovery, hall filter, fallback).
 * @see <a href="https://www.velodrom.de/events/event/joji-velodrom-2026-08-29">Example detail page</a>
 */
class VelomaxDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without a schema.org `Event` block
     * or a name in it.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and its [ScrapedEvent.sourceId].
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
            logger.warn { "Detail page has no schema.org Event block, skipping" }
            return null
        }
        val title = event.textAt("[itemprop=name]")?.let(::cleanEventTitle)
        if (title == null) {
            logger.warn { "Detail page has no event name, skipping" }
            return null
        }

        val slug = extractEventSlug(sourceUrl, EVENT_PATH_PREFIX)
        val subtitleLines = event.textLinesAt("[itemprop=alternateName]")
        val subtitle = subtitleLines.firstOrNull()
        val startedAt = event.attrAtProp("startDate", "datetime")
        val eventType = inferConcertVenueType(title)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            // Scoped to the single-event block: the teaser strip carries other events' prose.
            description = document.textAt(".eventSingle .event-content"),
            eventType = eventType,
            // `datetime` is a machine-readable "yyyy-MM-dd HH:mm:ss"; the rendered text is only "20:00 Uhr".
            eventDate = startedAt?.let { parseIsoDate(it.substringBefore(' ')) } ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(event.attrAtProp("doorTime", "datetime")?.clockTime()),
            startTime = parseTime(startedAt?.clockTime()),
            imageUrl = parseImageUrl(document, sourceUrl),
            sourceUrl = sourceUrl,
            // Show-level on purpose: one page per production, which is why a same-day run of sessions
            // cannot be keyed from here. The importer keeps the listing's session-keyed id
            // (`AbstractVelomaxHallImporter.fillGapsFromOverview`); this one stands in only when the
            // page is parsed alone.
            sourceId = "${hall.eventSource.sourceIdPrefix}$slug",
            ticketUrl = parseTicketUrl(document),
            status = parseSchemaEventStatus(event.attrAtProp("eventStatus", "content")),
            artists = buildArtistsForEventType(title, subtitleLines.joinToString("\n"), eventType),
            promoters = listOfNotNull(event.textAt("[itemprop=organizer] [itemprop=name]"))
        )
    }

    /**
     * The event's poster from the stage banner. Scoped to `section.stage`: the teaser strip
     * carries the *next* events' images, and the first `<img>` would belong to whichever of
     * those rendered first. Paths are site-relative (`/fileadmin/…`).
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

    /** The external ticket-shop link, in the performance block beside the times. */
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
