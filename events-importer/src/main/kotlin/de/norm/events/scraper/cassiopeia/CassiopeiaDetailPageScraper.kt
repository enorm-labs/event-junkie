package de.norm.events.scraper.cassiopeia

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.hasVisibleWebflowFlag
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Cassiopeia event detail pages.
 *
 * The detail page is the **primary data source**: it carries the description, the ticket URL and
 * clean `<img>` posters where the listing renders CSS `background-image`. What
 * [CassiopeiaOverviewPageScraper] parses is the fallback, and [CassiopeiaWebsiteImporter] merges the
 * two.
 *
 * Parsing is scoped to the `.modul-section.events` container, which holds every event field on the
 * page — that keeps navigation, footer and scripts out of the parse tree, so the selectors below can
 * be simple without matching chrome. The Webflow template gives the genre no class of its own: it is
 * the sibling *after* the `.subheading.invert.gap` category, which is the one positional read here
 * and the reason the category selector has to stay exact.
 *
 * **Artists are taken for concerts only.** Where the category is "Konzert" the title is the
 * headliner, and support acts are description paragraphs prefixed `"Support: "`. A party's title is
 * an event name, so nothing is minted from it.
 *
 * @see CassiopeiaOverviewPageScraper for the listing and the fallback fields.
 * @see CassiopeiaWebsiteImporter for the HTTP fetch orchestrator and the merge.
 */
@Suppress("TooManyFunctions") // Cohesive scraper with private helpers for each parsed field
class CassiopeiaDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses event data from a detail page document.
     *
     * Scopes all parsing to the `.modul-section.events` container, which
     * holds all event content on the detail page. Returns `null` if the
     * container is missing (unexpected page structure) or the page lacks
     * a title.
     *
     * Returns a [ScrapedEvent] containing only the fields that could be
     * extracted from this page. Fields that cannot be parsed are left at
     * their default values. The caller (typically [CassiopeiaWebsiteImporter])
     * is responsible for merging this with overview page data.
     *
     * The `sourceId` is derived from the [sourceUrl] path (e.g.
     * `https://cassiopeia-berlin.de/event/some-slug` → `cassiopeia:some-slug`),
     * matching the convention used by [CassiopeiaOverviewPageScraper].
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl]
     *   and to derive the [ScrapedEvent.sourceId].
     * @return the parsed event data, or `null` if the page lacks the expected
     *   content container or a title (indicating an unexpected page structure).
     */
    @Suppress("ReturnCount") // Guard clauses for missing container and missing title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        // Scope to the main event content container — all event data lives here.
        // This excludes navigation, footer, scripts, and other page chrome.
        val content = document.selectFirst(CONTENT_CONTAINER)
        if (content == null) {
            logger.warn { "Detail page at $sourceUrl has no '$CONTENT_CONTAINER' container, skipping" }
            return null
        }

        val title = content.textAt("h1.event-date.dark.event")
        if (title == null) {
            logger.warn { "Detail page at $sourceUrl has no title, skipping" }
            return null
        }

        val eventSlug = extractEventSlug(sourceUrl)
        val hasFlags = content.selectFirst(".flag-wrapper") != null
        val eventType = mapEventType(content.textAt(".subheading.invert.gap"))
        val eventDate =
            parseEventDate(content) ?: run {
                logger.warn { "Detail page at $sourceUrl has no parseable date, skipping" }
                return null
            }

        return ScrapedEvent(
            title = title,
            eventDate = eventDate,
            doorsTime = parseTimeByLabel(content, DOORS_LABEL),
            startTime = parseTimeByLabel(content, START_LABEL),
            eventType = eventType,
            genre = parseGenre(content),
            imageUrl = content.imgSrcAt("img.eventpage-image"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.CASSIOPEIA.sourceIdPrefix}$eventSlug",
            soldOut = hasFlags && content.hasVisibleWebflowFlag(FLAG_SELECTOR, "Sold-Out"),
            status =
                when {
                    hasFlags && content.hasVisibleWebflowFlag(FLAG_SELECTOR, "Cancelled") -> "CANCELLED"
                    else -> "SCHEDULED"
                },
            description = parseDescription(content),
            ticketUrl = parseTicketUrl(content),
            artists = parseArtists(title, eventType, content)
        )
    }

    /**
     * Extracts the event slug from the detail page URL path.
     *
     * Example: `https://cassiopeia-berlin.de/event/super-tuesday-111639689`
     * → `super-tuesday-111639689`
     */
    private fun extractEventSlug(sourceUrl: String): String {
        val path = URI(sourceUrl).path
        return path.removePrefix("/event/").trimEnd('/')
    }

    /**
     * Parses the event date from the detail page's date wrapper.
     *
     * The desktop date layout renders as `"16 . 05 . 2026"` when read via
     * [Element.text]. Stripping spaces yields `"16.05.2026"`, which we parse via
     * the shared [parseGermanDate][de.norm.events.scraper.parseGermanDate]. Time
     * wrappers (e.g. `"Einlass 19:00"`) won't match the format and return `null`,
     * so they are skipped automatically.
     */
    private fun parseEventDate(content: Element): LocalDate? =
        content.select(".date-wrapper").firstNotNullOfOrNull { wrapper ->
            parseGermanDate(wrapper.text().replace(" ", ""))
        }

    /**
     * Parses a time value by locating the label text within a `.date-wrapper`.
     *
     * The detail page structures times as label + value pairs inside
     * `.date-wrapper` elements. When read via [Element.text], this renders
     * as `"Einlass 19:00"` or `"Beginn 20:00"`. We check if the combined
     * text starts with the [label] and parse the remainder as a time.
     */
    private fun parseTimeByLabel(
        content: Element,
        label: String
    ): LocalTime? =
        content.select(".date-wrapper").firstNotNullOfOrNull { wrapper ->
            val text = wrapper.text().trim()
            if (!text.startsWith(label, ignoreCase = true)) return@firstNotNullOfOrNull null
            val timeText = text.removePrefix(label).trim()
            parseTime(timeText)
        }

    /**
     * Extracts the genre from the element adjacent to the category.
     *
     * On the detail page, category and genre are siblings:
     * ```html
     * <div class="subheading invert gap">Konzert</div>
     * <div class="subheading invert event-mobile line-clamp">Noise</div>
     * ```
     */
    private fun parseGenre(content: Element): String? {
        val categoryEl = content.selectFirst(".subheading.invert.gap") ?: return null
        return categoryEl
            .nextElementSibling()
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the event description from the detail page.
     *
     * The description is spread across multiple `.paragraph.events` divs
     * inside `.paragraph-wrapper`. Paragraphs are joined with newlines.
     */
    private fun parseDescription(content: Element): String? {
        val paragraphs =
            content
                .select(".paragraph-wrapper .paragraph.events")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
        return paragraphs.joinToString("\n").takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the ticket shop URL from the detail page.
     *
     * Uses multiple strategies in order of reliability:
     * 1. Look for an `a.faq-link-wrapper` containing "Tickets" text — this is the
     *    semantic pattern used by the Webflow CMS template.
     * 2. Fall back to any link pointing to Cassiopeia's Stager ticket shop domain.
     * 3. Fall back to the original `a.faq-link-wrapper.margin-bottom` selector
     *    for backwards compatibility.
     *
     * This layered approach avoids breaking when Webflow layout classes change
     * (e.g. `margin-bottom` being removed/renamed) while still matching the
     * correct element.
     */
    private fun parseTicketUrl(content: Element): String? {
        // Primary: semantic match — link with "Tickets" text inside `.faq-link-wrapper`
        val byText =
            content
                .select("a.faq-link-wrapper")
                .firstOrNull { link -> link.text().contains("Tickets", ignoreCase = true) }
                ?.attr("href")
                ?.takeIf { it.isNotBlank() && it.startsWith("http") }

        // Fallback: any link to the known ticket shop domain (stager.co)
        val byDomain = content.hrefAt("a[href*=stager.co]")

        // Last resort: original positional selector for backwards compatibility
        val byPosition = content.hrefAt("a.faq-link-wrapper.margin-bottom")

        return byText ?: byDomain ?: byPosition
    }

    /**
     * Extracts artists from a concert event's title and description paragraphs.
     *
     * For a `CONCERT`, the [title] is the headliner (co-bills split out), added
     * unconditionally — Cassiopeia titles are almost always the act. Genuine
     * event-name titles (e.g. "Grey City Fest Opener") are filtered structurally
     * by [isNonArtistName], and placeholders like "TBA" are dropped too. Support
     * acts come from description paragraphs prefixed "Support: " (e.g.
     * `"Support: Aska"`), following the headliner in listing order.
     *
     * Non-concert events (parties, etc.) never extract artists — their titles are
     * event names, not artist names.
     */
    private fun parseArtists(
        title: String,
        eventType: String?,
        content: Element
    ): List<ScrapedArtist> {
        // Only concert events use the "title = headliner" naming convention.
        if (eventType != "CONCERT") return emptyList()

        // Support acts from "Support: <name>" description paragraphs, in listing order.
        val supportActs =
            content
                .select(".paragraph-wrapper .paragraph.events")
                .map { it.text().trim() }
                .filter { it.startsWith(SUPPORT_PREFIX, ignoreCase = true) }
                .map { it.drop(SUPPORT_PREFIX.length).trim() }
                .filter { it.isNotBlank() && !isNonArtistName(it) }
                .map { ScrapedArtist(name = it, role = "SUPPORT") }

        return headlinersFromTitle(title) + supportActs
    }

    companion object {
        /** CSS selector for the main event content container on the detail page. */
        private const val CONTENT_CONTAINER = ".modul-section.events"

        /** CSS selector for sold-out / cancelled flag elements (Webflow conditional visibility). */
        private const val FLAG_SELECTOR = ".flag-wrapper .event-detail.sold-out"

        /** German label for doors/entry time. */
        private const val DOORS_LABEL = "Einlass"

        /** German label for show start time. */
        private const val START_LABEL = "Beginn"

        /** Prefix used in description paragraphs to identify support acts. */
        private const val SUPPORT_PREFIX = "Support: "
    }
}
