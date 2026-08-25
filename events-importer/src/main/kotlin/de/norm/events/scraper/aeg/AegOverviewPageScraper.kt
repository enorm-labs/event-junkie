package de.norm.events.scraper.aeg

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

/**
 * Pure HTML parser for the `/events/all` listing both Berlin AEG venues render — Uber Arena and the
 * Uber Eats Music Hall, which run one Carbonhouse tenant.
 *
 * Each venue publishes its whole programme server-side and unpaginated as `div[data-category]`
 * rows; the month buttons beside the list filter it client-side rather than paging the server. A
 * row carries the platform's category, a `.m-date__*` span group (day, month, year and an
 * `HH:mm Uhr` start), an `h3.event-title`, an optional `ab NN,NN €` from-price, a thumbnail, and a
 * link to `/events/detail/<slug>/<YYYY-MM-DD-HHMM>` — whose trailing segment makes the `sourceId`
 * unique per performance, since a run reuses one slug across many dates.
 *
 * Two things differ between the tenants and are handled here rather than in two near-identical
 * parsers:
 *  - the arena writes its month numerically (`08.`) while the music hall abbreviates it
 *    (`Sep. `, `Mär `), so both spellings are accepted;
 *  - the arena labels its category in `data-categoryname` while the music hall emits only the
 *    numeric `data-category`, so the name wins where present and the platform's numeric taxonomy
 *    ([AEG_CATEGORY_IDS], decoded from the arena, which publishes both) is the fallback.
 *
 * A cancelled date keeps its row and is prefixed `ABGESAGT:` in the title — the only place either
 * venue states a status — so that prefix becomes the status and is stripped from the stored name.
 *
 * **Sport rows are dropped** rather than stored as `OTHER`: the arena is home to ALBA Berlin and the
 * Eisbären, and filed as `OTHER` those fixtures would bury the concerts, shows and comedy nights
 * they sit among — the same call the Velomax halls make. They are also the only rows carrying a
 * `00:00 Uhr` placeholder start, so the filter removes that noise too.
 *
 * @see AegDetailPageScraper for the detail-page data (doors, description, ticket link).
 * @see AbstractAegVenueImporter for the HTTP fetch orchestrator.
 */
class AegOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every non-sport row from a venue's listing page.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve detail links.
     * @param eventSource the venue whose page this is, used for the `sourceId` prefix and logging.
     */
    fun scrape(
        document: Document,
        baseUrl: String,
        eventSource: EventSource
    ): List<ScrapedEvent> {
        val rows = document.select("div[data-category]")
        val (sport, ours) = rows.partition { isSport(it) }
        logger.info { "Found ${ours.size} event row(s) on $eventSource overview, skipping ${sport.size} sport fixture(s)" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed rows without aborting the import
        return ours.mapNotNull { row ->
            try {
                parseRow(row, baseUrl, eventSource)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse $eventSource event row, skipping" }
                null
            }
        }
    }

    /** Parses a single listing row into a [ScrapedEvent], or `null` when it has no link or title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseRow(
        row: Element,
        baseUrl: String,
        eventSource: EventSource
    ): ScrapedEvent? {
        val href = row.selectFirst("h3.event-title a[href]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        // The slug keeps its trailing "/YYYY-MM-DD-HHMM" segment: one production runs many dates.
        val slug = extractEventSlug(sourceUrl, "/events/detail/")

        val rawTitle = row.textAt("h3.event-title")
        val title = rawTitle?.replace(CANCELLED_PREFIX, "")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "$eventSource row '$slug' has no title, skipping" }
            return null
        }
        val eventType = mapEventType(categoryOf(row), EXTRA_CATEGORY_SYNONYMS)
        val price = row.textAt(".buttons .price")

        return ScrapedEvent(
            title = title,
            eventType = eventType,
            eventDate = parseRenderedDate(row) ?: UNRESOLVED_EVENT_DATE,
            startTime = parseTime(row.textAt(".m-date__hour")?.substringBefore(CLOCK_SUFFIX)?.trim()),
            imageUrl = row.imgSrcAt(".thumb img"),
            sourceUrl = sourceUrl,
            sourceId = "${eventSource.sourceIdPrefix}$slug",
            status = parseEventStatus(rawTitle),
            // The venues quote a cheapest-ticket price ("ab 83,50 €"); the note keeps the "from".
            pricePresale = parsePriceValue(price),
            priceNote = price,
            artists = buildArtistsForEventType(title, subtitle = null, eventType = eventType)
        )
    }

    /**
     * The row's category label. The arena states it outright; the music hall emits only the
     * platform's numeric id, decoded through [AEG_CATEGORY_IDS]. Returns `null` when the venue
     * filed the event under no category at all (id `0` — the music hall's one job fair).
     */
    private fun categoryOf(row: Element): String? =
        row.attr("data-categoryname").takeIf { it.isNotBlank() }
            ?: AEG_CATEGORY_IDS[row.attr("data-category")]

    /** Whether the row is a sport fixture under either the label or the numeric taxonomy. */
    private fun isSport(row: Element): Boolean {
        val name = row.attr("data-categoryname").takeIf { it.isNotBlank() } ?: AEG_CATEGORY_IDS[row.attr("data-category")]
        return name?.lowercase() in SPORT_CATEGORIES
    }

    /**
     * Assembles the date from the row's `.m-date__day` / `.m-date__month` / `.m-date__year` spans,
     * each carrying its own punctuation (`21.`, `08.` or `Sep. `, `2026,`). Returns `null` when a
     * part is missing or the combination is not a real date.
     */
    private fun parseRenderedDate(row: Element): LocalDate? {
        val day = row.textAt(".m-date__day")?.trim('.', ' ')?.toIntOrNull()
        val month = parseMonth(row.textAt(".m-date__month"))
        val year = row.textAt(".m-date__year")?.trim(',', ' ')?.toIntOrNull()
        return if (day == null || month == null || year == null) {
            null
        } else {
            runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
    }

    /** Reads the month either numerically (the arena) or as a German abbreviation (the music hall). */
    private fun parseMonth(text: String?): Int? {
        val trimmed = text?.trim('.', ' ') ?: return null
        return trimmed.toIntOrNull() ?: parseGermanMonthAbbreviation(trimmed)?.value
    }
}

/**
 * The platform's numeric category taxonomy, decoded from Uber Arena — the tenant that publishes
 * both `data-category` and `data-categoryname`. The music hall emits only the id, and its
 * programme corroborates the mapping (comedians under `4`, ballet under `5`, bands under `3`).
 */
private val AEG_CATEGORY_IDS =
    mapOf(
        "1" to "eishockey",
        "2" to "basketball",
        "3" to "konzert",
        "4" to "comedy",
        "5" to "show",
        "6" to "sport"
    )

/**
 * The categories dropped rather than imported. The arena is home to ALBA Berlin and the Eisbären,
 * so roughly a third of its listing is sport; filed as `OTHER` those would bury the concerts and
 * shows they sit among — the same decision as the Velomax halls. The music hall has no resident
 * team and so no such rows, but shares the filter.
 */
private val SPORT_CATEGORIES = setOf("eishockey", "basketball", "sport")

/** Category labels the shared table does not carry. A comedy night is a staged show. */
private val EXTRA_CATEGORY_SYNONYMS = mapOf("comedy" to EventType.SHOW.name)

/**
 * The cancellation marker the platform prefixes to a title (`ABGESAGT: Ryan Adams`). It is read
 * for the status and then removed, so the stored name stays the act's.
 */
private val CANCELLED_PREFIX = Regex("""^\s*ABGESAGT\s*[:.\-]?\s*""", RegexOption.IGNORE_CASE)

/** The trailing `Uhr` the venues append to their start time. */
private const val CLOCK_SUFFIX = "Uhr"
