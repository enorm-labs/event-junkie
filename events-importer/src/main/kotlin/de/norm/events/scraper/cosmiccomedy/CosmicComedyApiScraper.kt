package de.norm.events.scraper.cosmiccomedy

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * One page of Cosmic Comedy's events endpoint.
 *
 * @property events the page's events, already mapped.
 * @property nextPageUrl the API's own cursor to the next page, or `null` on the last one.
 */
data class CosmicComedyPage(
    val events: List<ScrapedEvent>,
    val nextPageUrl: String?
)

/**
 * Pure JSON parser for Cosmic Comedy Berlin's **The Events Calendar** REST API
 * (`/wp-json/tribe/events/v1/events`).
 *
 * The plugin's own API is used rather than the `Event` JSON-LD the listing page also embeds: the
 * JSON-LD covers only the page's current view (22 events at capture) where the API returns the
 * whole upcoming programme (57), and it carries the categories, organizers and full descriptions
 * the JSON-LD omits. It is the JSON source ADR-007 prefers over any HTML.
 *
 * A few things about this venue shape the mapping:
 *  - **Everything here is comedy**, so every event is a [EventType.SHOW]. The `categories` name a
 *    format or a language (`Showcase`, `Open Mic`, `Comedy Special`, `English Language`), never a
 *    musical genre, so nothing is stored as one.
 *  - **The programme is mostly one recurring house night.** 57 events resolve to 11 distinct
 *    titles; the `slug` is unique per date and is what identifies an event.
 *  - **No prices anywhere.** `cost` and `cost_details` are empty on every event.
 *  - **Titles and taxonomy names are HTML-escaped** (`&#8211;`, `&#8217;`) and the description is
 *    raw HTML opening with an embedded ticket-widget `<script>`, so both are decoded before use.
 *
 * @see CosmicComedyWebsiteImporter for the HTTP fetch orchestrator.
 */
class CosmicComedyApiScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .build()

    /**
     * Parses one page of the events endpoint.
     *
     * An unparseable body yields an empty page with no cursor, which stops paging rather than
     * aborting an import that may already hold earlier pages.
     *
     * @param json the raw response body.
     */
    @Suppress("TooGenericExceptionCaught") // A malformed payload must degrade to an empty page, never abort the import.
    fun scrapePage(json: String): CosmicComedyPage {
        val root =
            try {
                jsonMapper.readTree(json)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Cosmic Comedy events page" }
                return CosmicComedyPage(events = emptyList(), nextPageUrl = null)
            }

        val events =
            root.path("events").takeIf { it.isArray }.orEmptyNodes().mapNotNull { event ->
                @Suppress("TooGenericExceptionCaught") // Skip one malformed event without losing the page.
                try {
                    toScrapedEvent(event)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Cosmic Comedy event, skipping" }
                    null
                }
            }
        return CosmicComedyPage(
            events = events,
            nextPageUrl = root.path("next_rest_url").asString("").takeIf { it.isNotBlank() }
        )
    }

    /** Maps one API event, or `null` when it lacks the slug or start date that identify it. */
    @Suppress("ReturnCount") // Guard clauses for the required slug/date are clearer than nesting
    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val slug = event.path("slug").asString("").takeIf { it.isNotBlank() } ?: return null
        val start = parseLocalDateTime(event.path("start_date").asString("")) ?: return null
        val title = decode(event.path("title").asString(""))?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Cosmic Comedy event '$slug' has no title, skipping" }
            return null
        }
        val categories = event.path("categories").orEmptyNodes().mapNotNull { decode(it.path("name").asString("")) }

        return ScrapedEvent(
            title = title,
            description = htmlToText(event.path("description").asString("")),
            // The club programmes nothing but comedy, which the model files as a staged show.
            eventType = EventType.SHOW.name,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            imageUrl =
                event
                    .path("image")
                    .path("url")
                    .asString("")
                    .takeIf { it.isNotBlank() },
            sourceUrl = event.path("url").asString(""),
            sourceId = "${EventSource.COSMIC_COMEDY.sourceIdPrefix}$slug",
            ticketUrl = ticketUrl(event),
            artists = headlinerOf(title, categories),
            promoters = event.path("organizer").orEmptyNodes().mapNotNull { decode(it.path("organizer").asString("")) }
        )
    }

    /**
     * The performer, for the nights the club files as a `Comedy Special` — its own marker for a
     * named act rather than the house showcase. Those titles are all `"<Performer> – <Show>"`, so
     * the part before the dash is the act; a special without one yields no artist rather than a
     * guess. The recurring showcase and open-mic nights name no performer at all and get none.
     */
    private fun headlinerOf(
        title: String,
        categories: List<String>
    ): List<ScrapedArtist> {
        val performer =
            title
                .takeIf { categories.any { category -> category.equals(SPECIAL_CATEGORY, ignoreCase = true) } }
                ?.split(*TITLE_DASHES)
                ?.takeIf { it.size > 1 }
                ?.first()
                ?.trim()
        return listOfNotNull(performer?.takeIf { it.isNotBlank() }?.let { ScrapedArtist(name = it) })
    }

    /**
     * The ticket link: the event's own `website` where the venue set one, otherwise the Universe
     * listing embedded as a widget in the description.
     *
     * That widget is the club's season listing for its recurring nights, so most events share one
     * URL — it is still where their tickets are sold. Events with neither are stored without a link
     * rather than pointing at the venue's front page.
     */
    private fun ticketUrl(event: JsonNode): String? =
        event.path("website").asString("").takeIf { it.isNotBlank() }
            ?: UNIVERSE_WIDGET_PATTERN
                .find(event.path("description").asString(""))
                ?.groupValues
                ?.get(1)
                ?.let { "$UNIVERSE_EVENT_BASE$it" }

    /** Parses the API's local `"yyyy-MM-dd HH:mm:ss"` start, which is already in the venue's zone. */
    private fun parseLocalDateTime(text: String): LocalDateTime? =
        text.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDateTime.parse(it.trim(), API_DATE_TIME) }.getOrNull()
        }

    /** Decodes the HTML entities WordPress leaves in its titles and taxonomy names. */
    private fun decode(text: String): String? =
        Parser
            .unescapeEntities(text, false)
            .trim()
            .takeIf { it.isNotEmpty() }

    /**
     * Flattens the description's HTML to text. Parsing rather than stripping tags matters here: the
     * field opens with an embedded ticket-widget `<script>` whose body would otherwise land in the
     * stored description.
     */
    private fun htmlToText(html: String): String? =
        html
            .takeIf { it.isNotBlank() }
            ?.let { Jsoup.parseBodyFragment(it).body().text() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}

/** An array node's elements, or nothing at all for a missing or non-array field. */
private fun JsonNode?.orEmptyNodes(): List<JsonNode> = this?.takeIf { it.isArray }?.toList().orEmpty()

/** The plugin's local date-time format, stated in the venue's own timezone. */
private val API_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** The category the club puts on a night with a named act rather than its house showcase. */
private const val SPECIAL_CATEGORY = "Comedy Special"

/** The dashes the club separates a performer from their show title with. */
private val TITLE_DASHES = charArrayOf('–', '—')

/** Captures the Universe listing id out of the ticket widget embedded in a description. */
private val UNIVERSE_WIDGET_PATTERN = Regex("""data-target-id="([^"]+)"""")

/** Universe's public listing URL, to which a widget's target id is appended. */
private const val UNIVERSE_EVENT_BASE = "https://www.universe.com/events/"
