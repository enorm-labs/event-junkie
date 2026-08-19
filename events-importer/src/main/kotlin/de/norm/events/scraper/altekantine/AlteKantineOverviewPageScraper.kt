package de.norm.events.scraper.altekantine

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Pure HTML parser for Alte Kantine's homepage programme grid (overview page).
 *
 * The homepage renders every upcoming event as a Content Views item
 * (`.pt-cv-content-item`) carrying a `data-pid` WordPress post id and three
 * custom-field columns: a year-less `DD.MM.` date
 * (`.pt-cv-ctf-datum_der_veranstaltung`), a title link to the `?p=<id>` detail
 * post plus a short act line (`.pt-cv-ctf-veranstaltungsbeschreibung`), and a
 * `HH:mm Uhr` start time (`.pt-cv-ctf-beginn_der_veranstaltung`).
 *
 * The overview is the source for the event discovery list plus the date, start
 * time, title and act line. Because [AlteKantineWebsiteImporter] falls back to
 * this data when a detail page fails to fetch, each event is parsed as completely
 * as the listing allows — but the detail page ([AlteKantineDetailPageScraper]) is
 * the authoritative source for the event kind, price, description, image and DJ.
 *
 * The `DD.MM.` date carries no year, so it is resolved to the nearest occurrence
 * of that day/month around today via [inferYearForWeekday] (with no weekday to
 * disambiguate), matching the forward-looking programme the page shows.
 *
 * @see AlteKantineDetailPageScraper for the detail-page data source (kind, price, image, DJ).
 * @see AlteKantineWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://alte-kantine.eu/">Alte Kantine programme</a>
 */
class AlteKantineOverviewPageScraper(
    /** Clock for year inference on the year-less dates. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event items from the homepage document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the
     *   detail links and build `sourceId` values.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select(".pt-cv-content-item")
        logger.info { "Found ${items.size} event item(s) on overview page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the entire import
        return items.mapNotNull { item ->
            try {
                parseItem(item, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse event item, skipping" }
                null
            }
        }
    }

    /** Parses a single `.pt-cv-content-item` block into a [ScrapedEvent], or `null` if it has no title link or post id. */
    @Suppress("ReturnCount") // Guard clauses for the required link/post-id/title are clearer than nesting
    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val link = item.selectFirst(".pt-cv-ctf-veranstaltungsbeschreibung a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        // The WordPress post id is the stable canonical identity — read it from the URL's `?p=` query,
        // falling back to the item's own `data-pid` attribute.
        val postId = extractPostId(sourceUrl) ?: item.attr("data-pid").takeIf { it.isNotBlank() } ?: return null

        val title = link.text().trim().takeIf { it.isNotBlank() } ?: return null
        val subtitle = item.textAt(".pt-cv-ctf-veranstaltungsbeschreibung p")
        // The overview has no kind label, so classify from the title (a mixed party/quiz venue → OTHER default).
        val eventType = inferUnmarkedTitleType(title)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            eventDate = parseAlteKantineDate(item.textAt(".pt-cv-ctf-datum_der_veranstaltung"), clock) ?: UNRESOLVED_EVENT_DATE,
            startTime = parseAlteKantineTime(item.textAt(".pt-cv-ctf-beginn_der_veranstaltung")),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.ALTE_KANTINE.sourceIdPrefix}$postId",
            artists = buildAlteKantineArtists(title, subtitle, eventType)
        )
    }
}

/** Matches a year-less German `DD.MM.` date (with the optional trailing dot), e.g. "23.07." or "1.9". */
private val DAY_MONTH_PATTERN = Regex("""(\d{1,2})\.(\d{1,2})\.?""")

/**
 * Parses a year-less `DD.MM.` Alte Kantine date, resolving the missing year to the
 * occurrence of that day/month **nearest today** via [inferYearForWeekday] (no
 * weekday to disambiguate). This naturally rolls a January date listed in July
 * forward to next year while keeping the current programme in this year. Returns
 * `null` for null, blank, or unparseable input. Shared by the overview and detail
 * scrapers, whose `Wann:` / date columns use the same format.
 */
internal fun parseAlteKantineDate(
    text: String?,
    clock: Clock
): LocalDate? =
    text
        ?.let { DAY_MONTH_PATTERN.find(it) }
        ?.let { runCatching { MonthDay.of(it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull() }
        ?.let { inferYearForWeekday(it, weekday = null, clock = clock) }

/**
 * Parses an Alte Kantine `HH:mm Uhr` start time (e.g. "22:00 Uhr"), dropping the
 * trailing "Uhr" before delegating to [parseTime]. Returns `null` when absent or
 * unparseable. Shared by the overview and detail scrapers.
 */
internal fun parseAlteKantineTime(text: String?): LocalTime? = parseTime(text?.substringBefore("Uhr")?.trim())

/**
 * Extracts the WordPress post id from a `?p=<id>` permalink query, e.g.
 * `https://alte-kantine.eu/?p=12331` → `"12331"`, or `null` when the URL carries
 * no `p` query parameter. The post id is the event's stable canonical identity,
 * used to build a matching `sourceId` on both the overview and detail pages.
 */
internal fun extractPostId(url: String): String? =
    URI(url)
        .query
        ?.split("&")
        ?.firstOrNull { it.startsWith("p=") }
        ?.substringAfter("p=")
        ?.takeIf { it.isNotBlank() }

/**
 * Resolves the stored [EventType] name for an Alte Kantine event from its optional
 * `Was:` kind label and [title].
 *
 * The venue's own `Was:` label wins when it maps to a known type (e.g. "Party" →
 * `PARTY`, "Konzert" → `CONCERT`); otherwise — a free-text label like "The Quiz
 * Night Show", or the label-less overview — the title is classified by keyword,
 * defaulting to `OTHER` for this mixed party/quiz venue (never `CONCERT`, so an
 * unmarked party night is not minted as a headliner concert). Shared by both scrapers.
 */
internal fun alteKantineEventType(
    was: String?,
    title: String
): String = mapEventType(was) ?: inferUnmarkedTitleType(title)

/**
 * Builds the artist list for an Alte Kantine event, keyed off its resolved
 * [eventType]:
 * - **Concert** — the title carries the headliner act(s) (plus any support in the
 *   subtitle), via [buildArtistsForEventType].
 * - **Party** — the [act] line names the resident DJ, stored as a single `DJ` artist
 *   (dropped when it is a non-artist label).
 * - **Anything else** (quiz, other) — no performer: the title is an event-series
 *   name and the "DJ" line is often a format label like "Pubquiz".
 *
 * Shared by the overview scraper (where [act] is the listing's short act line) and
 * the detail scraper (where [act] is the `DJ:` field).
 */
internal fun buildAlteKantineArtists(
    title: String,
    act: String?,
    eventType: String?
): List<ScrapedArtist> =
    when (eventType) {
        EventType.CONCERT.name -> {
            buildArtistsForEventType(title, act, EventType.CONCERT.name)
        }

        EventType.PARTY.name -> {
            act
                ?.takeUnless { isNonArtistName(it) }
                ?.let { listOf(ScrapedArtist(name = it, role = "DJ")) }
                .orEmpty()
        }

        else -> {
            emptyList()
        }
    }
