package de.norm.events.scraper.clash

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseGermanShortDate
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DateTimeException
import java.time.LocalTime

/**
 * Pure HTML parser for Clash Berlin's WordPress homepage event listing.
 *
 * All upcoming events render inline in the homepage `#events` section as a flat list of
 * `.gigs-container .item` blocks — no per-event page (the `event` post type is not on the WP
 * REST API, and the numeric `/events/<id>/` permalinks 404). Each block carries a full
 * `DD.MM.YY` date in a `.dateTwo` span, a title, an optional lineup subtitle, a start time, a
 * poster, and for ticketed shows a Stager ticket-shop link.
 *
 * A live-music (punk/ska) club that also hosts quiz, party and festival nights, so the type is
 * inferred from the title ([inferConcertVenueType] — CONCERT by default). Acts come from the
 * lineup subtitle (see [parseArtists]); the rest is sparse — no doors time, prices, genre or
 * promoters — so those fields stay unset.
 *
 * @see ClashWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://clash-berlin.de/">Clash Berlin</a>
 */
class ClashOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the Clash homepage.
     *
     * @param baseUrl the URL the document was fetched from, for resolving the per-event anchor link.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select(".gigs-container .item")
        logger.info { "Found ${items.size} event item(s) on Clash homepage" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the entire import
        return items.mapNotNull { item ->
            try {
                parseItem(item, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Clash event item, skipping" }
                null
            }
        }
    }

    /**
     * Parses one `.item` block into a [ScrapedEvent], or `null` when title or date is missing/unparseable.
     */
    @Suppress("ReturnCount") // Null-safe early exits for the required title/date fields are clearer than nesting
    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val title = item.textAt(".gig-title")
        if (title.isNullOrBlank()) {
            logger.warn { "Clash event item has no title, skipping" }
            return null
        }

        // Prefer the full `DD.MM.YY` date in the collapsed detail (carries the year); the
        // `.date-label` above shows only day + English month abbreviation.
        val eventDate = parseGermanShortDate(item.textAt(".dateTwo"))
        if (eventDate == null) {
            logger.warn { "Could not parse event date for '$title', skipping" }
            return null
        }

        // The collapse panel id encodes DDMMYYYY + WordPress post id (e.g. "2906202619490"); stable per
        // event and doubles as the homepage deep-link anchor.
        val collapseId = item.selectFirst(".collapse.infofull")?.id()?.takeIf { it.isNotBlank() }
        val slug = collapseId ?: "$eventDate-${SlugGenerator.slugify(title)}"

        val subtitle = item.textAt("h4.sub-title")
        val startTime = parseTime(item.textAt(".meta .time"))
        val imageUrl = item.imgSrcAt(".flyer img")
        // The per-event ticket link points at the Stager shop's `/events/<id>` page; the bare
        // `/shop/tickets/` link in the section header is not inside an `.item`.
        val ticketUrl = item.hrefAt("a[href*=stager.co/shop/tickets/events]")

        // No category field; infer from the title (concert by default for this live-music venue,
        // quiz/party/etc. by keyword). See inferConcertVenueType.
        val eventType = inferConcertVenueType(title)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            eventDate = eventDate,
            startTime = startTime,
            imageUrl = imageUrl,
            // No per-event pages — deep-link to the expanded event on the homepage listing.
            sourceUrl = resolveUrl(baseUrl, "#$slug"),
            sourceId = "${EventSource.CLASH.sourceIdPrefix}$slug",
            ticketUrl = ticketUrl,
            artists = parseArtists(subtitle, eventType)
        )
    }

    /**
     * The performing acts from a concert's lineup subtitle. The `h4.sub-title` lists
     * slash-separated acts, sometimes behind a "Live:" / "DJ:" label (`"Live: Cheb Balowski /
     * Cuatro Pesos de Propina"`, `"Popperklopper / Hausvabot / Ad Nauseam"`). The first act is the
     * headliner, the rest support.
     *
     * Only a subtitle that *looks* like a lineup is used — a "Live:"/"DJ:" label or an act
     * separator (`/`, `+`). A plain-prose tagline ("Last Show Ever in Berlin") has neither and
     * yields no artists. Restricted to CONCERT-typed events (quiz/party/other carry no lineup);
     * the title is deliberately not an artist source because Clash titles are frequently event
     * names ("Kneipenquiz", festival days). Non-performers (placeholders, festival/segment
     * labels) are dropped via [isNonArtistName].
     */
    private fun parseArtists(
        subtitle: String?,
        eventType: String?
    ): List<ScrapedArtist> {
        val lineup =
            subtitle
                ?.takeIf { eventType == EventType.CONCERT.name && looksLikeLineup(it) }
                ?.replaceFirst(LINEUP_LABEL_PREFIX, "")
                ?: return emptyList()

        return splitSupportActs(lineup)
            .map { stripArtistSuffix(it) }
            .filterNot { isNonArtistName(it) }
            .mapIndexed { index, name ->
                ScrapedArtist(name = name, role = if (index == 0) "HEADLINER" else "SUPPORT")
            }
    }

    /** Whether [subtitle] carries a lineup marker (a "Live:"/"DJ:" label or a `/`/`+` act separator). */
    private fun looksLikeLineup(subtitle: String): Boolean = LINEUP_LABEL_PREFIX.containsMatchIn(subtitle) || subtitle.contains('/') || subtitle.contains('+')

    /**
     * The start time from the meta line ("Fri 20:00", "Mon 0:00"). The hour may be a single digit
     * ("0:00"), which the strict `HH:mm` parser rejects, so the `H:mm`/`HH:mm` value is extracted
     * by regex and built directly.
     */
    private fun parseTime(text: String?): LocalTime? {
        val match = text?.let { TIME_PATTERN.find(it) } ?: return null
        return try {
            LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    companion object {
        /** An `H:mm` / `HH:mm` clock time from the meta line's "<weekday> <time>" text. */
        private val TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})""")

        /** Leading lineup label on a subtitle ("Live:", "DJ:", "DJs:", "Line-up:"), stripped before splitting acts. */
        private val LINEUP_LABEL_PREFIX = Regex("""^\s*(?:live|djs?|line[\s-]?up)\s*:\s*""", RegexOption.IGNORE_CASE)
    }
}
