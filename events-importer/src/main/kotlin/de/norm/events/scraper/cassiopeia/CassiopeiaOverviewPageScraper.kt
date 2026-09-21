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
 * Pure HTML parser for Cassiopeia's Webflow `/club` listing, `.event-item` elements. Discovery
 * of every detail URL, plus fallback fields (title, date, times, category, genre, image,
 * status) so an event survives a failed detail fetch. Paginated by Finsweet CMS Load via
 * JavaScript, so only the first page is scraped. Recently-passed events are dropped here, after
 * dedup and before the detail fetch, purely to save HTTP; persistence applies the same cutoff.
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
     * Parses all event items, then deduplicates by date + title: Webflow CMS occasionally lists one
     * event twice, `/event/doll` (legacy) and `/event/doell-111601080` (canonical, with the CMS
     * numeric ID), and the numeric-suffixed entry is preferred as the stable URL.
     *
     * @param sourceUrl the URL the document was fetched from, for relative links and `sourceId`s.
     * @return upcoming [ScrapedEvent]s (today onward).
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
     * Removes duplicates (same date + title), preferring a slug with a Webflow CMS numeric ID suffix
     * (`doell-111601080` over `doll`), which is unique within the CMS and stable across renames.
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
     * Whether a sourceId ends in a Webflow CMS numeric ID (`cassiopeia:doell-111601080`, not
     * `cassiopeia:doll`).
     */
    private fun hasCmsNumericId(sourceId: String): Boolean {
        val slug = sourceId.substringAfter(":")
        return CMS_NUMERIC_SUFFIX.containsMatchIn(slug)
    }

    /**
     * Parses one `.event-item`. The element carries a desktop and a mobile layout; the mobile
     * `.event-details` elements hold the most structured data (doors, start, category, genre
     * separately). Selectors prefer semantic attributes (Finsweet's `fs-cmsfilter-field`) and label
     * text over positional classes, which a Webflow redesign renumbers.
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
        // Times by label text ("Einlass"/"Beginn") rather than the positional `._5`, `._8` classes.
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
            // For concerts the title is the headliner, a fallback for a failed detail fetch; support acts
            // come from the detail page and win in the merge.
            artists = if (eventType == "CONCERT") headlinersFromTitle(title) else emptyList()
        )
    }

    /**
     * The date from `.event-date-wrapper`, whose `h2.event-date` children and a hidden "faker" read
     * as `"14 . 05 . Mai 2026"`; the numeric parts are extracted by regex, skipping the month name.
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
     * A time from the mobile section by label: the `.event-details` element matching [label] and
     * its next sibling, falling back to the positional `._5`, `._8` classes for older layouts.
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
     * The image from `background-image: url(...)` on `.event-image-wrapper`, which Webflow uses
     * instead of `<img>`; falls back to an `<img>` child.
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
