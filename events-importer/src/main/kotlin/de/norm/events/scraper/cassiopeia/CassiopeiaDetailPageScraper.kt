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
 * Pure HTML parser for Cassiopeia event detail pages, the primary data source: the description,
 * the ticket URL and clean `<img>` posters where the listing renders CSS `background-image`.
 * [CassiopeiaOverviewPageScraper] is the fallback, [CassiopeiaWebsiteImporter] merges. Parsing
 * is scoped to `.modul-section.events`, which holds every field, keeping chrome out of the
 * tree. The Webflow template gives the genre no class of its own: it is the sibling after the
 * `.subheading.invert.gap` category, the one positional read and why the category selector must
 * stay exact. Artists for concerts only: where the category is "Konzert" the title is the
 * headliner and support acts are description paragraphs prefixed `"Support: "`; a party's title
 * is an event name.
 *
 * @see CassiopeiaOverviewPageScraper for the listing and the fallback fields.
 * @see CassiopeiaWebsiteImporter for the HTTP fetch orchestrator and the merge.
 */
@Suppress("TooManyFunctions") // Cohesive scraper with private helpers for each parsed field
class CassiopeiaDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page, scoped to `.modul-section.events`; `null` if the container or a title
     * is missing. Only the fields this page yields are set; the importer merges the rest. The
     * `sourceId` is derived from the [sourceUrl] path
     * (`https://cassiopeia-berlin.de/event/some-slug` to `cassiopeia:some-slug`), as the overview
     * does.
     *
     * @param sourceUrl the event's URL, [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId]
     * source.
     * @return the parsed event, or `null` on an unexpected page structure.
     */
    @Suppress("ReturnCount") // Guard clauses for missing container and missing title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        // Scope to the event content container; navigation, footer and scripts are outside it.
        val content = document.selectFirst(CONTENT_CONTAINER)
        if (content == null) {
            logger.warn { "Detail page has no '$CONTENT_CONTAINER' container, skipping" }
            return null
        }

        val title = content.textAt("h1.event-date.dark.event")
        if (title == null) {
            logger.warn { "Detail page has no title, skipping" }
            return null
        }

        val eventSlug = extractEventSlug(sourceUrl)
        val hasFlags = content.selectFirst(".flag-wrapper") != null
        val eventType = mapEventType(content.textAt(".subheading.invert.gap"))
        val eventDate =
            parseEventDate(content) ?: run {
                logger.warn { "Detail page has no parseable date, skipping" }
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

    /** Extracts the event slug from the detail page URL path. */
    private fun extractEventSlug(sourceUrl: String): String {
        val path = URI(sourceUrl).path
        return path.removePrefix("/event/").trimEnd('/')
    }

    /**
     * The date from the date wrapper: the desktop layout reads as `"16 . 05 . 2026"`, spaces
     * stripped to `"16.05.2026"` for [parseGermanDate][de.norm.events.scraper.parseGermanDate].
     * Time wrappers (`"Einlass 19:00"`) fail the format and return `null`, so they are skipped.
     */
    private fun parseEventDate(content: Element): LocalDate? =
        content.select(".date-wrapper").firstNotNullOfOrNull { wrapper ->
            parseGermanDate(wrapper.text().replace(" ", ""))
        }

    /**
     * A time by its label inside a `.date-wrapper`, which reads as `"Einlass 19:00"` or `"Beginn
     * 20:00"`; the remainder after [label] is parsed.
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
     * The genre from the sibling after the category:
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
     * The description: the `.paragraph.events` divs inside `.paragraph-wrapper`, joined by newlines.
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
     * The ticket URL, by reliability: an `a.faq-link-wrapper` containing "Tickets", the Webflow
     * template's semantic pattern; any link to Cassiopeia's Stager domain; the original
     * `a.faq-link-wrapper.margin-bottom` selector. Layered so a renamed layout class
     * (`margin-bottom`) does not break it.
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
     * Artists from a concert's title and description. For a `CONCERT` the [title] is the headliner,
     * co-bills split, added unconditionally; event-name titles ("Grey City Fest Opener") and
     * placeholders are filtered by [isNonArtistName]. Support acts are paragraphs prefixed
     * "Support: " (`"Support: Aska"`), after the headliner. Non-concert events extract nothing.
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
