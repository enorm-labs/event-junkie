package de.norm.events.scraper.ufafabrik

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.textLines
import org.jsoup.nodes.Document

/**
 * Pure parser for one show page, `/veranstaltung/<node>/<slug>`: the blurb in the article's
 * `field--name-body`, one paragraph per line. A bare link, a photo credit and the house's line on
 * which vouchers it does not take are dropped; they are not about the show. The event carries the
 * blurb only, since the month row owns every other field.
 */
class UfaFabrikEventPageScraper {
    fun scrape(
        document: Document,
        url: String
    ): ScrapedEvent? =
        description(document)?.let { ScrapedEvent(title = "", description = it, eventDate = UNRESOLVED_EVENT_DATE, sourceUrl = url, sourceId = "") }

    private fun description(document: Document): String? =
        document
            .select("article .field--name-body p")
            .map { it.textLines().joinToString("\n") }
            .filterNot { it.isEmpty() || NOT_ABOUT_THE_SHOW.containsMatchIn(it) }
            .joinToString("\n")
            .ifEmpty { null }

    private companion object {
        val NOT_ABOUT_THE_SHOW = Regex("""^(?:https?://\S+|fotos?\s*:.*)$|können nicht eingelöst werden""", RegexOption.IGNORE_CASE)
    }
}
