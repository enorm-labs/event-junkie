package de.norm.events.scraper.eighthundreda

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.SCRAPER_JSON
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseOffsetToBerlin
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Duration

/**
 * Pure parser for the 800A home page, an Astro site on Supabase. The `EventsSection` island's props carry the programme as
 * the site's own event rows: `calendarEvents` for the next three months, `events` for the next few weeks only.
 *
 * - **The category types the event and sets the scope.** `Workshop` holds the paid courses and is out (EVENT_SCOPE.md §3.2).
 * - **A start whose end follows within a minute is a placeholder.** The editor saves a date with the time of entry, as
 *   `14:38 – 14:39`, and the event page shows it as given. Such a row keeps its date and has no time.
 * - A donation night is `is_free` and `is_donation_based` together, and is not free (#3011).
 * - `host_name` is the external collective only when `is_external_host` is set. Otherwise it repeats a performer.
 * - The rows also carry the house's business fields, such as rent and expected revenue. The parser reads none of them.
 */
class EightHundredAHomePageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val props = document.selectFirst("astro-island[component-url*=EventsSection][props]")?.attr("props")
        val rows = props?.let(::calendarRows)
        if (rows == null) {
            logger.warn { "No calendarEvents in the 800A EventsSection island" }
            return emptyList()
        }
        val events = rows.mapSkippingFailures(logger, "800A event") { toEvent(it) }
        logger.info { "Found ${rows.size} 800A row(s), ${events.size} public and in scope from $baseUrl" }
        return events
    }

    /** Astro serialises each prop as `[type, value]`, and this one's value is itself a JSON string. */
    private fun calendarRows(props: String): List<JsonNode>? =
        try {
            SCRAPER_JSON
                .readTree(props)
                .path("calendarEvents")
                .path(1)
                .asString("")
                .blankToNull()
                ?.let { SCRAPER_JSON.readTree(it).toList() }
        } catch (e: JacksonException) {
            logger.warn(e) { "Unreadable EventsSection props on the 800A page" }
            null
        }

    private fun toEvent(row: JsonNode): ScrapedEvent? {
        val category = row.path("category").stringOrNull("slug")
        if (!isPublic(row) || category == WORKSHOP) return null
        val title = row.stringOrNull("title_en") ?: return null
        val start = row.stringOrNull("starts_at").parseOffsetToBerlin() ?: return null
        val end = row.stringOrNull("ends_at").parseOffsetToBerlin()
        val timed = end == null || Duration.between(start, end) > PLACEHOLDER_SPAN
        val doors = row.stringOrNull("doors_open_at").parseOffsetToBerlin()?.takeIf { timed && it.isBefore(start) }
        val donation = row.path("is_donation_based").asBoolean(false)
        val slug = row.stringOrNull("share_slug") ?: return null
        return ScrapedEvent(
            title = title,
            subtitle = row.stringOrNull("subtitle_en")?.takeUnless { it.equals(title, ignoreCase = true) },
            description = row.stringOrNull("description_en"),
            descriptionAlt = row.stringOrNull("description_de")?.takeUnless { it == row.stringOrNull("description_en") },
            eventType = TYPES[category] ?: EventType.OTHER.name,
            typeIsFallback = category !in TYPES,
            eventDate = start.toLocalDate(),
            doorsTime = doors?.toLocalTime(),
            startTime = start.toLocalTime().takeIf { timed },
            endDate = end?.takeIf { timed }?.toLocalDate(),
            endTime = end?.takeIf { timed }?.toLocalTime(),
            imageUrl = row.stringOrNull("image_path")?.let { "$IMAGE_BASE$it" },
            sourceUrl = "$EVENT_PAGE_BASE$slug",
            sourceId = "${EventSource.EIGHT_HUNDRED_A.sourceIdPrefix}${row.stringOrNull("id") ?: return null}",
            ticketUrl = row.stringOrNull("ticket_url"),
            pricePresale =
                row
                    .path("price_euros")
                    .takeIf { it.isNumber }
                    ?.decimalValue()
                    ?.takeIf { it > BigDecimal.ZERO },
            priceNote = if (donation) donationNote(row) else null,
            free = row.path("is_free").asBoolean(false) && !donation,
            artists = artistsOf(row.stringOrNull("performers")),
            promoters = listOfNotNull(row.stringOrNull("host_name")?.takeIf { row.path("is_external_host").asBoolean(false) })
        )
    }

    private fun isPublic(row: JsonNode): Boolean =
        row.stringOrNull("status") == PUBLISHED && !row.path("is_private").asBoolean(false) && !row.path("is_paused").asBoolean(false)

    private fun donationNote(row: JsonNode): String {
        val min = row.path("donation_min").asInt(0)
        val max = row.path("donation_max").asInt(0)
        return when {
            min > 0 && max > min -> "Donation $min–$max €"
            min > 0 -> "Donation from $min €"
            else -> "Donation"
        }
    }

    private fun artistsOf(performers: String?): List<ScrapedArtist> =
        performers
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isNonArtistName(it) }
            .distinct()
            .map { ScrapedArtist(name = it) }

    private companion object {
        const val PUBLISHED = "published"
        const val WORKSHOP = "workshop"
        const val EVENT_PAGE_BASE = "https://www.800aberlin.com/events/"
        const val IMAGE_BASE = "https://pkuixlkyxclroyymxkyf.supabase.co/storage/v1/object/public/event-images/"
        val PLACEHOLDER_SPAN: Duration = Duration.ofMinutes(1)

        /** `special` is the house's one-off staged night, such as Stripper Stories. */
        val TYPES =
            mapOf(
                "music" to EventType.CONCERT.name,
                "comedy" to EventType.COMEDY.name,
                "burlesque" to EventType.SHOW.name,
                "theatre" to EventType.SHOW.name,
                "special" to EventType.SHOW.name,
                "film" to EventType.SCREENING.name
            )
    }
}
