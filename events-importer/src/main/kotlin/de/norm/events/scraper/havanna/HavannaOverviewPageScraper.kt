package de.norm.events.scraper.havanna

import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.time.DayOfWeek

/** A weekly night teased on the `/events` overview: where its page lives, and the poster shown for it. */
data class HavannaNightLink(
    /** Absolute URL of the night's page, e.g. `https://www.havanna-berlin.de/friday`. */
    val url: String,
    /** Weekday the night runs on, derived from [url]. */
    val dayOfWeek: DayOfWeek,
    /** Poster teased on the overview, used when the night's own page carries none. */
    val imageUrl: String? = null
)

/**
 * Pure HTML parser for Havanna Berlin's `/events` overview page.
 *
 * The page is a static Squarespace three-column row — no dates, no titles, no event text. Each column
 * holds a poster image block and a "More" button linking to one of the venue's three undated weekly
 * night pages (`/wednesday`, `/friday`, `/saturday`). So the overview is purely a discovery step: it
 * yields the night URLs and their posters, and [HavannaDetailPageScraper] reads the actual programme.
 *
 * Links are kept only when their path names a weekday, which filters out the site's other buttons
 * (the footer's "Subscribe", a night page's "‹ Back to Events") without depending on the button text.
 *
 * @see HavannaWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.havanna-berlin.de/events">Havanna events page</a>
 */
class HavannaOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the night links from the overview page.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the relative night links.
     * @return one [HavannaNightLink] per weekly night, in page order and de-duplicated by URL.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<HavannaNightLink> {
        val columns = document.select("$MAIN_CONTENT .col:has(a.sqs-block-button-element[href])")
        logger.info { "Found ${columns.size} teaser column(s) on the Havanna events page" }

        return columns
            .mapNotNull { column ->
                val href = column.selectFirst("a.sqs-block-button-element[href]")?.attr("href").orEmpty()
                val url = href.takeIf { it.isNotBlank() }?.let { resolveUrl(baseUrl, it) } ?: return@mapNotNull null
                val dayOfWeek =
                    havannaWeekdayFromUrl(url) ?: run {
                        logger.debug { "Ignoring non-night link on the Havanna events page: $url" }
                        return@mapNotNull null
                    }
                HavannaNightLink(url = url, dayOfWeek = dayOfWeek, imageUrl = column.imgSrcAt("img"))
            }.distinctBy { it.url }
    }

    private companion object {
        /** Squarespace marks the page's editable body with this content field, keeping header/footer blocks out. */
        const val MAIN_CONTENT = "[data-content-field=\"main-content\"]"
    }
}
