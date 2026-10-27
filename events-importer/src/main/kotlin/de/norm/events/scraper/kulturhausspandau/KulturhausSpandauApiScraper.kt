package de.norm.events.scraper.kulturhausspandau

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.SCRAPER_JSON
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.childrensShowPhrase
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.forChildrenIf
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.hasSoldOutMarker
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.stripSoldOutMarker
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure parser for the theme's `/wp-json/eventmanager/v1/events`, which answers about 250 events, past ones included, with
 * place, categories, artists and price tiers but no text.
 *
 * - **Only the house's own rooms are read.** The list also carries the district's events at the Zitadelle, which has its own
 *   importer, and in the Altstadt churches. A place counts when its street is the house's, `Mauerstr. 6`.
 * - **The categories type the event and set the scope.** Tours, workshops and exhibitions are out (EVENT_SCOPE.md §3.2), and so
 *   is the `Mitsingding` sing-along. `Kinder und Familie` or a show for an age range under thirteen is marked `for-children` (§3.5).
 * - **A title can carry an editor's tag**, `<p hidden KreativWerk_2.12.26>`, and a `<br>-AUSVERKAUFT` tail.
 * - The `Eintritt` tier is the price. `ermäßigt` and `Kinder` are concessions, and a tier with `active: false` is unused.
 */
class KulturhausSpandauApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        json: String,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val nodes = SCRAPER_JSON.readTree(json).toList()
        val events = nodes.mapSkippingFailures(logger, "Kulturhaus Spandau event") { toEvent(it) }
        logger.info { "Found ${nodes.size} Kulturhaus Spandau entr(ies), ${events.size} in the house and in scope from $sourceUrl" }
        return events
    }

    private fun toEvent(node: JsonNode): ScrapedEvent? {
        if (node.path("place").stringOrNull("street") != HOUSE_STREET) return null
        val id = node.path("ID").asLong(0).takeIf { it > 0 } ?: return null
        val rawTitle = node.stringOrNull("title")?.let { decodeHtmlEntities(it.replace(EDITOR_TAG, " ")) } ?: return null
        val subtitle = node.stringOrNull("subtitle")?.let(::decodeHtmlEntities)
        val categories =
            node
                .path("categories")
                .toList()
                .mapNotNull { it.stringOrNull("name") }
                .toSet()
        if (isOutOfScope(categories, rawTitle)) return null
        val (title, kicker) = headline(cleanEventTitle(stripSoldOutMarker(rawTitle).trim(' ', '-', '–')).text, subtitle?.trim())
        val start = node.stringOrNull("start")?.let { LocalDateTime.parse(it, API_TIME) } ?: return null
        val end = node.stringOrNull("end")?.let { LocalDateTime.parse(it, API_TIME) }?.takeIf { it.isAfter(start) }
        val admission = node.path("ticketprices").toList().firstOrNull { it.path("active").asBoolean(false) && it.stringOrNull("title") == "Eintritt" }
        val admissionText = admission?.stringOrNull("price")
        val type = typeOf(categories)
        return ScrapedEvent(
            title = title,
            subtitle = kicker?.takeUnless { it.equals(title, ignoreCase = true) },
            eventType = type,
            typeIsFallback = categories.isEmpty(),
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime(),
            room = node.path("place").stringOrNull("title")?.takeUnless { it == HOUSE_NAME },
            imageUrl = node.stringOrNull("mainpicture"),
            sourceUrl = node.stringOrNull("permalink") ?: return null,
            sourceId = "${EventSource.KULTURHAUS_SPANDAU.sourceIdPrefix}$id",
            ticketUrl = node.stringOrNull("ticketlink"),
            pricePresale = parsePriceValue(admissionText),
            priceNote = admissionText?.takeIf { parsePriceValue(it) == null }?.trim(),
            free = hasFreeEntryPhrase(admissionText) || admissionText?.trim()?.startsWith("frei", ignoreCase = true) == true,
            soldOut = hasSoldOutMarker(rawTitle),
            artists =
                node
                    .path("artists")
                    .toList()
                    .mapNotNull { it.stringOrNull("title")?.let(::decodeHtmlEntities)?.blankToNull() }
                    .map { ScrapedArtist(it) }
        ).forChildrenIf(childrensPhrase(categories, rawTitle, subtitle, node.path("maximum_age").asInt(0)))
    }

    /**
     * The page sets the subtitle above the title as a kicker. Two shapes read wrongly without it: the slam's bare `Spandau`
     * under `Poetry Slam`, and a list of disciplines (`Live-Gesang | Tanz | Artistik`) under the show's name.
     */
    private fun headline(
        title: String,
        subtitle: String?
    ): Pair<String, String?> =
        when {
            subtitle.isNullOrEmpty() -> title to null
            title == HOUSE_DISTRICT -> "$subtitle $title" to null
            DISCIPLINE_LIST in title -> subtitle to title
            else -> title to subtitle
        }

    private fun isOutOfScope(
        categories: Set<String>,
        title: String
    ): Boolean = categories.any { it in PARTICIPATION } || SING_ALONG.containsMatchIn(title)

    /** The category `Kinder und Familie`, a `maximum_age` of [CHILDRENS_MAXIMUM_AGE] or less, or [childrensShowPhrase]. */
    private fun childrensPhrase(
        categories: Set<String>,
        title: String,
        subtitle: String?,
        maximumAge: Int
    ): String? =
        CHILDREN_AND_FAMILY.takeIf { it in categories }
            ?: "bis $maximumAge Jahre".takeIf { maximumAge in 1..CHILDRENS_MAXIMUM_AGE }
            ?: childrensShowPhrase(title, subtitle)

    /** A reading first, since every slam is also filed under comedy; a concert with `Show` is a staged revue. */
    private fun typeOf(categories: Set<String>): String =
        when {
            "Lesung" in categories -> EventType.READING.name
            "Comedy und Kabarett" in categories -> EventType.COMEDY.name
            "Konzert" in categories && "Show" !in categories -> EventType.CONCERT.name
            categories.any { it in STAGE || it == CHILDREN_AND_FAMILY } -> EventType.SHOW.name
            "Vortrag" in categories -> EventType.READING.name
            else -> EventType.OTHER.name
        }
}

private const val HOUSE_STREET = "Mauerstr. 6"
private const val HOUSE_NAME = "Kulturhaus Spandau"
private const val HOUSE_DISTRICT = "Spandau"
private const val DISCIPLINE_LIST = " | "
private const val CHILDREN_AND_FAMILY = "Kinder und Familie"

/** A show capped at this age is for children; one for teenagers states no cap. */
private const val CHILDRENS_MAXIMUM_AGE = 12

private val PARTICIPATION = setOf("Führung", "Workshop", "Ausstellung")
private val STAGE = setOf("Show", "Theater", "Zauberei", "Konzert")
private val API_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** The audience is the choir (Passionskirche's precedent, EVENT_SCOPE.md §3.2). */
private val SING_ALONG = Regex("""mitsing""", RegexOption.IGNORE_CASE)

/** An editor's hidden `<p hidden …>` tag, or a `<br>`, inside a title. */
private val EDITOR_TAG = Regex("""<p\s+hidden[^>]*>|<br\s*/?>""", RegexOption.IGNORE_CASE)
