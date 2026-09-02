package de.norm.events.scraper.cassiopeia

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.dropPastEvents
import de.norm.events.scraper.hasVisibleWebflowFlag
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Cassiopeia's Webflow-based event listing (overview) page.
 *
 * Extracts event data from `.event-item` elements on the `/club` page.
 *
 * The listing page serves two purposes:
 * 1. **Discovery** — identifies all event URLs for detail page fetching.
 * 2. **Fallback data** — extracts core event fields (title, date, times,
 *    category, genre, image, status) so that events remain importable
 *    even when their detail page cannot be fetched.
 *
 * **Important**: The listing page is paginated (Webflow CMS pagination).
 * Only the first page of events is scraped. The site uses Finsweet
 * CMS Load to load additional pages via JavaScript, which is not
 * available to server-side scraping.
 *
 * The listing can carry recently-passed events. Persistence drops past-dated events
 * centrally (`EventUpsertService`), but this scraper also filters them here — after
 * dedup, before the detail-page fetch — purely as an optimization, so no HTTP is
 * wasted fetching detail pages for events that would be discarded anyway.
 *
 * @see CassiopeiaDetailPageScraper for the primary per-event data source.
 * @see <a href="https://cassiopeia-berlin.de/club">Cassiopeia Club page</a>
 */
class CassiopeiaOverviewPageScraper(
    /** Clock for the past-event cutoff. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event items from the overview page document.
     *
     * After parsing, duplicate events (same date + title) are deduplicated.
     * Webflow CMS occasionally lists the same event twice with different
     * URL slugs — e.g. `/event/doll` (legacy) and `/event/doell-111601080`
     * (canonical with CMS numeric ID). When duplicates are found, the entry
     * with a Webflow CMS numeric ID suffix is preferred because it is the
     * stable, canonical URL.
     *
     * @param sourceUrl the URL the document was fetched from, used for
     *   resolving relative links and building `sourceId` values.
     * @return a list of upcoming [ScrapedEvent] instances (today onward) extracted from
     *   the page; recently-passed events are dropped here to avoid wasted detail-page
     *   fetches — persistence enforces the same cutoff regardless.
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val eventItems = document.select(".event-item")
        logger.info { "Found ${eventItems.size} event item(s) on page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the entire import
        val events =
            eventItems.mapNotNull { item ->
                try {
                    parseEventItem(item, sourceUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse event item, skipping" }
                    null
                }
            }

        return deduplicateEvents(events).dropPastEvents(clock) { dropped ->
            logger.info { "Dropped $dropped past event(s) from Cassiopeia listing" }
        }
    }

    /**
     * Removes duplicate events (same date + title), preferring entries
     * whose URL slug contains a Webflow CMS numeric ID suffix.
     *
     * Webflow CMS can create duplicate listings when an event is re-created
     * or when both a legacy slug and a canonical slug exist simultaneously.
     * The canonical URL pattern includes a numeric CMS item ID at the end
     * (e.g. `doell-111601080`), while legacy slugs are plain text (`doll`).
     * The numeric-suffixed URL is preferred because it is globally unique
     * within the CMS and stable across renames.
     */
    private fun deduplicateEvents(events: List<ScrapedEvent>): List<ScrapedEvent> {
        // Group by date + normalized title to find duplicates
        val grouped = events.groupBy { "${it.eventDate}|${it.title.trim().lowercase()}" }

        return grouped.values.map { group ->
            if (group.size == 1) {
                group.first()
            } else {
                // Prefer the entry with a CMS numeric ID in the slug (globally unique and stable)
                val preferred = group.firstOrNull { hasCmsNumericId(it.sourceId) } ?: group.first()
                val dropped = group.filter { it !== preferred }
                dropped.forEach { dup ->
                    logger.info {
                        "Deduplicating '${dup.title}' on ${dup.eventDate}: " +
                            "keeping ${preferred.sourceId}, dropping ${dup.sourceId}"
                    }
                }
                preferred
            }
        }
    }

    /**
     * Checks whether a sourceId contains a Webflow CMS numeric ID suffix.
     *
     * Canonical Webflow slugs end with a hyphen followed by a numeric CMS
     * item ID (e.g. `cassiopeia:doell-111601080`). Legacy or manually created
     * slugs lack this suffix (e.g. `cassiopeia:doll`).
     */
    private fun hasCmsNumericId(sourceId: String): Boolean {
        val slug = sourceId.substringAfter(":")
        return CMS_NUMERIC_SUFFIX.containsMatchIn(slug)
    }

    /**
     * Parses a single `.event-item` element into a [ScrapedEvent].
     *
     * The Webflow CMS structure uses two layouts — desktop and mobile —
     * within the same element. We primarily use the mobile layout's
     * `.event-details` elements because they contain the most structured
     * data (doors time, start time, category, genre as separate elements).
     *
     * Where possible, selectors prefer semantic attributes (e.g. Finsweet's
     * `fs-cmsfilter-field`) and label text over positional CSS classes,
     * because semantic identifiers are less likely to change during a
     * Webflow redesign.
     */
    private fun parseEventItem(
        item: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val link =
            item.selectFirst("a.event-wrapper")
                ?: error("No event link found")
        val href = link.attr("href")
        val eventSlug = href.removePrefix("/event/")

        // Prefer Finsweet CMS filter attribute — the most stable selector for the title
        val title =
            item.textAt("[fs-cmsfilter-field=title]")
                ?: item.textAt(".event-title-wrapper h2")
                ?: error("No title found")

        val eventDate =
            parseEventDate(item) ?: run {
                logger.warn { "Could not parse event date for '$title', skipping event" }
                return null
            }
        // Use label text ("Einlass"/"Beginn") to locate times rather than positional CSS classes,
        // because label text is semantic content unlikely to change, while numbered classes
        // (e.g. `._5`, `._8`) are positional and fragile across layout changes.
        val doorsTime = parseTimeByLabel(item, DOORS_LABEL)
        val startTime = parseTimeByLabel(item, START_LABEL)
        // Prefer Finsweet CMS filter attributes for category and genre
        val category = item.textAt("[fs-cmsfilter-field=category]")
        val genre = item.textAt("[fs-cmsfilter-field=genre]")
        val imageUrl = parseImageUrl(item)
        val isSoldOut = item.hasVisibleWebflowFlag(FLAG_SELECTOR, "Sold-Out")
        val isCancelled = item.hasVisibleWebflowFlag(FLAG_SELECTOR, "Cancelled")
        val eventUrl = resolveUrl(sourceUrl, href)
        val eventType = mapEventType(category)

        return ScrapedEvent(
            title = title,
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            eventType = eventType,
            genre = genre,
            imageUrl = imageUrl,
            sourceUrl = eventUrl,
            sourceId = "${EventSource.CASSIOPEIA.sourceIdPrefix}$eventSlug",
            soldOut = isSoldOut,
            status = if (isCancelled) "CANCELLED" else "SCHEDULED",
            // For concerts the title is the headliner — extracted here as a fallback
            // for when the detail-page fetch fails. Support acts (and richer detail
            // artists) come from the detail page and win in the importer merge.
            artists = if (eventType == "CONCERT") headlinersFromTitle(title) else emptyList()
        )
    }

    /**
     * Parses the event date from the structured date elements.
     *
     * The `.event-date-wrapper` contains `h2.event-date` elements for
     * day, dots, month, and a hidden "faker" with month name + year.
     * Calling `text()` on the wrapper yields e.g. `"14 . 05 . Mai 2026"`.
     * We extract all numeric parts — day (14), month (05), year (2026) —
     * via regex, which also naturally skips the German month name.
     */
    @Suppress("ReturnCount") // Null-safe early exits for each date component are clearer than nested let-chains
    private fun parseEventDate(item: Element): LocalDate? {
        val wrapperText = item.selectFirst(".event-date-wrapper")?.text() ?: return null
        val numericParts = NUMERIC_PATTERN.findAll(wrapperText).map { it.value }.toList()
        if (numericParts.size < DATE_PARTS_COUNT) return null

        val day = numericParts[0].toIntOrNull() ?: return null
        val month = numericParts[1].toIntOrNull() ?: return null
        val year = numericParts[2].toIntOrNull() ?: return null

        return try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * Parses a time value from the mobile detail section by locating the label text.
     *
     * Finds the `.event-details` element whose text matches the [label]
     * (e.g. "Einlass", "Beginn") and reads the time from the next sibling.
     * Falls back to positional CSS classes (`._5`, `._8`) if the label
     * is not found, for backwards compatibility with older page layouts.
     */
    private fun parseTimeByLabel(
        item: Element,
        label: String
    ): LocalTime? {
        // Primary: find by label text — semantic and robust against layout changes
        val timeText =
            item
                .selectFirst(".event-details-overwrapper .event-details:containsOwn($label)")
                ?.nextElementSibling()
                ?.text()
                ?.trim()
                // Fallback: positional CSS class convention (_5 for doors, _8 for start)
                ?: run {
                    val fallbackClass = if (label == DOORS_LABEL) "_5" else "_8"
                    item.selectFirst(".event-details.$fallbackClass")?.text()?.trim()
                }

        return parseTime(timeText)
    }

    /**
     * Extracts the event image URL from the background-image CSS property.
     *
     * Webflow renders event images as `background-image: url(...)` on
     * the `.event-image-wrapper` div rather than as `<img>` elements.
     * Falls back to an `<img>` child element if the background-image
     * style is absent or empty — in case the Webflow template changes.
     */
    private fun parseImageUrl(item: Element): String? {
        val wrapper = item.selectFirst(".event-image-wrapper") ?: return null
        val style = wrapper.attr("style")

        // Primary: extract from CSS background-image property
        val fromStyle =
            style
                .takeUnless { it.isBlank() || it.contains("background-image:none", ignoreCase = true) }
                ?.let { IMAGE_URL_PATTERN.find(it)?.groupValues?.get(1) }
                ?.replace("&quot;", "")
                ?.replace("\"", "")
                ?.takeIf { it.isNotBlank() }

        // Fallback: look for an <img> element inside the wrapper
        val fromImg =
            wrapper
                .selectFirst("img[src]")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() && it.startsWith("http") }

        return fromStyle ?: fromImg
    }

    companion object {
        /** CSS selector for sold-out / cancelled flag elements (Webflow conditional visibility). */
        private const val FLAG_SELECTOR = ".flag-wrapper .event-detail.sold-out"

        /** German label for doors/entry time used in the mobile detail section. */
        private const val DOORS_LABEL = "Einlass"

        /** German label for show start time used in the mobile detail section. */
        private const val START_LABEL = "Beginn"

        /** Expected number of numeric parts in a date wrapper (day, month, year). */
        private const val DATE_PARTS_COUNT = 3

        /** Regex to match numeric-only strings (day, month, year). */
        private val NUMERIC_PATTERN = Regex("""\d+""")

        /** Regex to extract the URL from a CSS `background-image: url(...)` property. */
        private val IMAGE_URL_PATTERN = Regex("""url\(([^)]+)\)""")

        /** Regex matching a Webflow CMS numeric ID suffix at the end of a slug. */
        private val CMS_NUMERIC_SUFFIX = Regex("""-\d{6,}$""")
    }
}
