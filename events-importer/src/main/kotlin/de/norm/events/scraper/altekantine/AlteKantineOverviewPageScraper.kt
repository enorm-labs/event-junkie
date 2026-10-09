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
import de.norm.events.scraper.mapSkippingFailures
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
 * Every upcoming event is a Content Views item (`.pt-cv-content-item`) with a `data-pid`
 * WordPress post id and three custom-field columns: a year-less `DD.MM.` date
 * (`.pt-cv-ctf-datum_der_veranstaltung`), a title link to the `?p=<id>` detail post plus a
 * short act line (`.pt-cv-ctf-veranstaltungsbeschreibung`), and a `HH:mm Uhr` start time
 * (`.pt-cv-ctf-beginn_der_veranstaltung`).
 *
 * The overview is the discovery list plus date, start time, title and act line.
 * [AlteKantineWebsiteImporter] falls back to it when a detail page fails, so each event is
 * parsed as completely as the listing allows — but the detail page ([AlteKantineDetailPageScraper])
 * is authoritative for kind, price, description, image and DJ.
 *
 * The `DD.MM.` date carries no year; it resolves to the nearest occurrence of that day/month
 * around today via [inferYearForWeekday] (no weekday to disambiguate), matching the
 * forward-looking programme.
 *
 * @see AlteKantineDetailPageScraper for the detail-page data source (kind, price, image, DJ).
 * @see AlteKantineWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://alte-kantine.eu/">Alte Kantine programme</a>
 */
class AlteKantineOverviewPageScraper(
    /** Clock for year inference on the year-less dates; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event items from the homepage.
     *
     * @param baseUrl the URL the document was fetched from, for detail links and `sourceId` values.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select(".pt-cv-content-item")
        logger.info { "Found ${items.size} event item(s) on overview page" }

        return items.mapSkippingFailures(logger, "event item") { item ->
            parseItem(item, baseUrl)
        }
    }

    /** Parses one `.pt-cv-content-item` into a [ScrapedEvent], or `null` without a title link or post id. */
    @Suppress("ReturnCount") // Guard clauses for the required link/post-id/title are clearer than nesting
    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val link = item.selectFirst(".pt-cv-ctf-veranstaltungsbeschreibung a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        // The WordPress post id is the stable identity — from the URL's `?p=` query, else the item's
        // `data-pid`.
        val postId = extractPostId(sourceUrl) ?: item.attr("data-pid").takeIf { it.isNotBlank() } ?: return null

        val title = link.text().trim().takeIf { it.isNotBlank() } ?: return null
        val subtitle = item.textAt(".pt-cv-ctf-veranstaltungsbeschreibung p")
        // No kind label on the overview, so classify from the title (mixed party/quiz venue → OTHER default).
        val eventType = alteKantineEventType(was = null, title = title)

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

/** A year-less German `DD.MM.` date (optional trailing dot), e.g. "23.07." or "1.9". */
private val DAY_MONTH_PATTERN = Regex("""(\d{1,2})\.(\d{1,2})\.?""")

/**
 * Parses a year-less `DD.MM.` date, resolving the year to the occurrence of that day/month
 * **nearest today** via [inferYearForWeekday] (no weekday to disambiguate) — a January date
 * listed in July rolls forward to next year while the current programme stays in this year.
 * `null` for null, blank or unparseable input. Shared by both scrapers, whose `Wann:` / date
 * columns use the same format.
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
 * Parses a `HH:mm Uhr` start time ("22:00 Uhr"), dropping the "Uhr" before [parseTime].
 * `null` when absent or unparseable. Shared by both scrapers.
 */
internal fun parseAlteKantineTime(text: String?): LocalTime? = parseTime(text?.substringBefore("Uhr")?.trim())

/**
 * The WordPress post id from a `?p=<id>` permalink (`https://alte-kantine.eu/?p=12331` →
 * `"12331"`), or `null` without a `p` parameter. The stable identity, building a matching
 * `sourceId` on both pages.
 */
internal fun extractPostId(url: String): String? =
    URI(url)
        .query
        ?.split("&")
        ?.firstOrNull { it.startsWith("p=") }
        ?.substringAfter("p=")
        ?.takeIf { it.isNotBlank() }

/**
 * A reading title: `lesung`, or a word ending in `lesen` — the weekly "Kantinenlesen" (#2969).
 * "Auserlesene" does not match. `(?!\p{L})` rather than `\b`, which ends a word at an umlaut.
 */
private val READING_TITLE = Regex("""lesen(?!\p{L})|lesung""", RegexOption.IGNORE_CASE)

/**
 * The stored [EventType] name from the optional `Was:` kind label and [title]. A reading title
 * wins first, because the venue labels its reading `Was: Vortrag`, which the shared table leaves
 * unmapped. Then the venue's label wins when it maps to a known type ("Party" → `PARTY`,
 * "Konzert" → `CONCERT`); otherwise — a free-text label like "The Quiz Night Show", or the
 * label-less overview — the title is classified by keyword, defaulting to `OTHER` for this
 * mixed party/quiz venue (never `CONCERT`, so an unmarked party night is not minted as a
 * headliner concert). Shared by both.
 */
internal fun alteKantineEventType(
    was: String?,
    title: String
): String =
    EventType.READING.name.takeIf { READING_TITLE.containsMatchIn(title) }
        ?: mapEventType(was)
        ?: inferUnmarkedTitleType(title)

/**
 * The artist list keyed off the resolved [eventType]:
 * - **Concert** — the title carries the headliner(s) (plus any support in the subtitle), via
 * [buildArtistsForEventType].
 * - **Party** — the [act] line names the resident DJ, one `DJ` artist (dropped when a
 * non-artist label).
 * - **Anything else** (quiz, other) — no performer: the title is a series name and the "DJ"
 * line is often a format label like "Pubquiz".
 *
 * Shared by the overview ([act] is the listing's act line) and the detail scraper ([act] is `DJ:`).
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
