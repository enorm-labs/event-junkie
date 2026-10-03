package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// The reader for a WordPress venue on The Events Calendar, whose REST API
// (`/wp-json/tribe/events/v1/events`) returns the whole upcoming programme. The venue maps each
// event; the page shape, the cursor walk and the field readers live here.

private val logger = KotlinLogging.logger {}

private val tecMapper: JsonMapper = JsonMapper.builder().build()

/** The plugin's local date-time format, in the venue's own timezone. */
private val TEC_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** The plugin's largest page, so a programme takes the fewest requests. */
private const val TEC_PER_PAGE = 50

/** One page of the events endpoint: its events, mapped, and the API's own cursor to the next page, or null on the last. */
data class TecPage(
    val events: List<ScrapedEvent>,
    val nextPageUrl: String?
)

/**
 * Parses one page of the events endpoint, mapping each event with [toEvent]. A body that does
 * not parse is an empty last page, so the walk keeps the pages it already read. An event that
 * fails to map is skipped with a warning.
 */
fun parseTecPage(
    json: String,
    source: EventSource,
    toEvent: (JsonNode) -> ScrapedEvent?
): TecPage {
    val root =
        try {
            tecMapper.readTree(json)
        } catch (e: JacksonException) {
            logger.warn(e) { "${source.name} events page is not parseable JSON" }
            return TecPage(events = emptyList(), nextPageUrl = null)
        }
    val events =
        root.path("events").tecNodes().mapSkippingFailures(logger, "${source.name} event") { event ->
            toEvent(event)
        }
    return TecPage(events, root.stringOrNull("next_rest_url"))
}

/**
 * Walks the events endpoint at [url] from its first page, fifty events a page, following the
 * API's `next_rest_url` up to [maxPages]. The cursor carries the date window the first page
 * chose; a hand-built `page=2` without it answers 404.
 */
suspend fun ApiClient.walkTecEvents(
    source: EventSource,
    url: String,
    maxPages: Int,
    scrapePage: (String) -> TecPage
): WalkedListing<ScrapedEvent> {
    val firstUrl = "$url${url.querySeparator()}per_page=$TEC_PER_PAGE"
    val fetchPage: suspend (String) -> TecPage = { scrapePage(fetchJson(it)) }
    return walkListingPages(source, fetchPage(firstUrl), firstUrl, maxPages, fetchPage) { page, _ -> ListingPage(page.events, page.nextPageUrl) }
}

/**
 * Base class for a venue on The Events Calendar: walk its events endpoint with [walkTecEvents] and
 * return every page's events. The API sends no validators, so every run is a [ImportResult.Success].
 * [maxPages] is the runaway guard; [venueName] is how the log line names the source.
 */
abstract class AbstractTecImporter(
    private val apiClient: ApiClient,
    private val venueName: String,
    private val scrapePage: (String) -> TecPage,
    private val maxPages: Int
) : EventImporter {
    // javaClass.name, so the log names the concrete importer rather than this base.
    private val importLogger = KotlinLogging.logger(javaClass.name)

    final override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val listing = apiClient.walkTecEvents(eventSource, url, maxPages, scrapePage)
        importLogger.info { "Scraped ${listing.items.size} event(s) from $venueName across ${listing.pages} page(s)" }
        return ImportResult.Success(events = listing.items, etag = null, lastModified = null, complete = listing.complete)
    }
}

/** The local `start_date` or `end_date`, or null when missing or malformed. */
fun JsonNode.tecDateTime(field: String): LocalDateTime? =
    stringOrNull(field)?.let {
        try {
            LocalDateTime.parse(it, TEC_DATE_TIME)
        } catch (_: DateTimeParseException) {
            null
        }
    }

/** A text field with the HTML entities WordPress leaves in titles and taxonomy names decoded. */
fun JsonNode.tecText(field: String): String? = stringOrNull(field)?.let(::decodeHtmlEntities)?.blankToNull()

/** The `description` HTML as text. Parsed, not stripped, so an embedded `<script>` widget leaves no code behind. */
fun JsonNode.tecDescription(): String? = stringOrNull("description")?.let { Jsoup.parseBodyFragment(it).body().text() }.blankToNull()

/** The featured image's full-size URL. */
fun JsonNode.tecImageUrl(): String? = path("image").stringOrNull("url")

/** The decoded `name` of each entry in `categories` or `tags`. */
fun JsonNode.tecTermNames(field: String): List<String> = path(field).tecNodes().mapNotNull { it.tecText("name") }

/** The decoded name of each organizer. */
fun JsonNode.tecOrganizerNames(): List<String> = path("organizer").tecNodes().mapNotNull { it.tecText("organizer") }

/** An array node's elements; nothing for a missing or non-array field. */
private fun JsonNode.tecNodes(): List<JsonNode> = takeIf { it.isArray }?.toList().orEmpty()
