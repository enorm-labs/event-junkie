package de.norm.events.scraper.pandaplatforma

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.hasSoldOutMarker
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTecPage
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.tecDateTime
import de.norm.events.scraper.tecDescription
import de.norm.events.scraper.tecImageUrl
import de.norm.events.scraper.tecOrganizerNames
import de.norm.events.scraper.tecTermNames
import de.norm.events.scraper.tecText
import de.norm.events.scraper.withBilingualDescriptionSplit
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * Pure JSON parser for PANDA platforma's **The Events Calendar** REST API; the page shape and
 * the field readers are [parseTecPage]'s.
 *
 * - **The categories are the house series** (`PANDAjazz`, `PANDAtheatre`, `PANDAtext` …), and
 * they type the night. A text or poetry series outranks music: a singer's poetry evening is filed
 * under both, and it is a reading.
 * - **A `PANDAgoes` night runs at another club** and names it as the event's `venue`, so only the
 * events at a venue record named for the house are kept.
 * - **A jazz night credits its players as `<strong>Name</strong> – instrument`**, often once per
 * language. Those lines are the lineup; other nights name nobody outside the prose.
 * - **`cost_details` holds one value, two for a range, `0` for free.**
 */
class PandaPlatformaApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.PANDA_PLATFORMA, ::toScrapedEvent)

    @Suppress("ReturnCount") // Guard clauses for the required fields and the venue are clearer than nesting
    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val slug = event.stringOrNull("slug") ?: return null
        val start = event.tecDateTime("start_date") ?: return null
        val title = event.tecText("title")?.let { cleanEventTitle(it.replace(SERIES_PREFIX, "")) }
        if (title.isNullOrBlank()) {
            logger.warn { "PANDA platforma event '$slug' has no title, skipping" }
            return null
        }
        if (event.path("venue").tecText("venue")?.equals(HOUSE_NAME, ignoreCase = true) != true) {
            logger.debug { "Skipping PANDA platforma event '$title': it runs at another venue" }
            return null
        }
        val categories = event.tecTermNames("categories").map { it.lowercase() }
        val eventType = typeOf(categories)
        val end = event.tecDateTime("end_date")?.takeIf { it.isAfter(start) }
        val prices = event.path("cost_details").path("values").mapNotNull { it.asString("").toBigDecimalOrNull() }

        return ScrapedEvent(
            title = title,
            description = event.tecDescription(),
            eventType = eventType ?: inferUnmarkedTitleType(title),
            typeIsFallback = eventType == null,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime(),
            imageUrl = event.tecImageUrl(),
            sourceUrl = event.stringOrNull("url") ?: return null,
            sourceId = "${EventSource.PANDA_PLATFORMA.sourceIdPrefix}$slug",
            ticketUrl = event.stringOrNull("website")?.takeIf { it.isNotBlank() },
            genre = categories.mapNotNull { STYLE_BY_CATEGORY[it] }.joinToString(", ").ifEmpty { null },
            pricePresale = prices.singleOrNull()?.takeIf { it.signum() > 0 },
            priceNote = event.tecText("cost")?.takeIf { prices.size > 1 },
            free = prices.singleOrNull()?.compareTo(BigDecimal.ZERO) == 0,
            soldOut = hasSoldOutMarker(title),
            artists = if (eventType == EventType.CONCERT.name) creditedPlayers(event) else emptyList(),
            // The association's own organizer entry is the venue, not a promoter.
            promoters = event.tecOrganizerNames().filterNot { it.startsWith(HOUSE_NAME, ignoreCase = true) }
        ).withBilingualDescriptionSplit()
    }

    /** The type the series name, or null for an event filed under none. */
    private fun typeOf(categories: List<String>): String? =
        when {
            categories.any { it in TEXT_CATEGORIES } -> EventType.READING.name
            "pandatheatre" in categories -> EventType.SHOW.name
            "pandaexhibition" in categories -> EventType.EXHIBITION.name
            categories.any { it in MUSIC_CATEGORIES } -> EventType.CONCERT.name
            else -> null
        }

    /** The players of a `<strong>Name</strong> – instrument` credit list, each once. */
    private fun creditedPlayers(event: JsonNode): List<ScrapedArtist> =
        PLAYER_CREDIT
            .findAll(event.path("description").asString(""))
            .map { decodeHtmlEntities(it.groupValues[1]).trim() }
            .filterNot { it.isBlank() || isNonArtistName(it) }
            .distinctBy { it.lowercase() }
            .map { ScrapedArtist(name = it) }
            .toList()

    private companion object {
        /** The house's name on its venue records, of which there are two; a `PANDAgoes` night names the club it visits. */
        const val HOUSE_NAME = "PANDA platforma"

        /** A series label opening the title, `PANDAjazz: Die Ursonate`. */
        val SERIES_PREFIX = Regex("""^PANDA\p{L}+:\s+""")

        /** One credited player: a bold name, a dash, then the instrument. */
        val PLAYER_CREDIT = Regex("""<strong>([^<]{2,60})</strong>\s*(?:&#8211;|–|-)\s*\p{L}""")

        val TEXT_CATEGORIES = setOf("pandatext", "pandapoetry")
        val MUSIC_CATEGORIES = setOf("pandamusic", "pandajazz", "pandaexperimental", "pandaglobal")
        val STYLE_BY_CATEGORY = mapOf("pandajazz" to "Jazz", "pandaexperimental" to "Experimental", "pandaglobal" to "World")
    }
}
