package de.norm.events.scraper.cosmiccomedy

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hasSoldOutMarker
import de.norm.events.scraper.parseTecPage
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.tecDateTime
import de.norm.events.scraper.tecDescription
import de.norm.events.scraper.tecImageUrl
import de.norm.events.scraper.tecOrganizerNames
import de.norm.events.scraper.tecTermNames
import de.norm.events.scraper.tecText
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode

/**
 * Pure JSON parser for Cosmic Comedy Berlin's **The Events Calendar** REST API
 * (`/wp-json/tribe/events/v1/events`).
 *
 * The plugin's API rather than the `Event` JSON-LD the listing also embeds: the JSON-LD covers
 * only the page's current view (22 events at capture) where the API returns the whole upcoming
 * programme (57), with the categories, organizers and full descriptions the JSON-LD omits. The
 * JSON source ADR-007 prefers over any HTML.
 *
 * - **Everything here is comedy**, so every event is a [EventType.SHOW]. The `categories` name
 * a format or a language (`Showcase`, `Open Mic`, `Comedy Special`, `English Language`), never
 * a musical genre, so nothing is stored as one.
 * - **The programme is mostly one recurring house night.** 57 events resolve to 11 distinct
 * titles; the `slug` is unique per date and identifies an event.
 * - **No prices anywhere.** `cost` and `cost_details` are empty on every event.
 * - **Sold out is only in the title** (`LATE SHOW (SOLD OUT!!!)`): the API has no stock field.
 * - **The description opens with an embedded Universe ticket widget**, whose target id is the
 * ticket link where the event sets no `website`.
 *
 * @see CosmicComedyWebsiteImporter for the HTTP fetch orchestrator.
 * @see de.norm.events.scraper.parseTecPage for the page shape and the field readers.
 */
class CosmicComedyApiScraper {
    private val logger = KotlinLogging.logger {}

    /** Parses one page of the events endpoint; [parseTecPage] says how a malformed page or event degrades. */
    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.COSMIC_COMEDY, ::toScrapedEvent)

    /** Maps one API event, or `null` when it lacks the slug or start date that identify it. */
    @Suppress("ReturnCount") // Guard clauses for the required slug/date are clearer than nesting
    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val slug = event.stringOrNull("slug") ?: return null
        val start = event.tecDateTime("start_date") ?: return null
        val title = event.tecText("title")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Cosmic Comedy event '$slug' has no title, skipping" }
            return null
        }
        val categories = event.tecTermNames("categories")

        return ScrapedEvent(
            title = title,
            description = event.tecDescription(),
            // The club programmes nothing but comedy.
            eventType = EventType.COMEDY.name,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            imageUrl = event.tecImageUrl(),
            sourceUrl = event.path("url").asString(""),
            sourceId = "${EventSource.COSMIC_COMEDY.sourceIdPrefix}$slug",
            ticketUrl = ticketUrl(event),
            soldOut = hasSoldOutMarker(title),
            artists = headlinerOf(title, categories),
            promoters = event.tecOrganizerNames()
        )
    }

    /**
     * The performer, for nights filed as a `Comedy Special` — the club's marker for a named act
     * rather than the house showcase. Those titles are all `"<Performer> – <Show>"`, so the part
     * before the dash is the act; a special without one yields no artist rather than a guess. The
     * recurring showcase and open-mic nights name no performer and get none.
     *
     * **A night filed as both a special and a [SHOWCASE_CATEGORY] is the house showcase**, whatever
     * the marker says, and it names no performer either. The club's own recurring night is titled
     * `"Comedy, Pizza and Shots – SHOWCASE FRIDAY"`, and one edition of it went out as `"Laughs,
     * Pizza & Shots – English Comedy Night in the Heart of Berlin!"` carrying both categories: the
     * part before the dash was stored as an act, so a night's name became an artist (#1789). Across
     * the live programme every other special names a real comedian and carries no showcase
     * category, so the pair is the signal and nothing about the string has to be guessed at.
     */
    private fun headlinerOf(
        title: String,
        categories: List<String>
    ): List<ScrapedArtist> {
        val performer =
            title
                .takeIf { namesAPerformer(categories) }
                ?.split(TITLE_DASHES)
                ?.takeIf { it.size > 1 }
                ?.first()
                ?.trim()
        return listOfNotNull(performer?.takeIf { it.isNotBlank() }?.let { ScrapedArtist(name = it) })
    }

    /** Whether [categories] mark a named act: a [SPECIAL_CATEGORY] that is not also a showcase. */
    private fun namesAPerformer(categories: List<String>): Boolean =
        categories.any { it.equals(SPECIAL_CATEGORY, ignoreCase = true) } &&
            categories.none { it.equals(SHOWCASE_CATEGORY, ignoreCase = true) }

    /**
     * The ticket link: the event's own `website` where set, otherwise the Universe listing embedded
     * as a widget in the description. That widget is the club's season listing for its recurring
     * nights, so most events share one URL — still where their tickets are sold. Events with
     * neither are stored without a link rather than pointing at the front page.
     */
    private fun ticketUrl(event: JsonNode): String? =
        event.path("website").asString("").takeIf { it.isNotBlank() }
            ?: UNIVERSE_WIDGET_PATTERN
                .find(event.path("description").asString(""))
                ?.groupValues
                ?.get(1)
                ?.let { "$UNIVERSE_EVENT_BASE$it" }
}

/** The category the club puts on a night with a named act rather than its house showcase. */
private const val SPECIAL_CATEGORY = "Comedy Special"

/** The category of the club's recurring house night, which names a format and never an act. */
private const val SHOWCASE_CATEGORY = "Showcase"

/** The dashes the club separates a performer from their show title with. */
private val TITLE_DASHES = Regex("[–—]")

/** Captures the Universe listing id out of the ticket widget embedded in a description. */
private val UNIVERSE_WIDGET_PATTERN = Regex("""data-target-id="([^"]+)"""")

/** Universe's public listing URL, to which a widget's target id is appended. */
private const val UNIVERSE_EVENT_BASE = "https://www.universe.com/events/"
