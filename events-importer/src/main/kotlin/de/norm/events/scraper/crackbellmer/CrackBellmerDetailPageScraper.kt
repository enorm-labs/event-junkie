package de.norm.events.scraper.crackbellmer

import org.jsoup.nodes.Document

/**
 * Pure HTML parser for a Crack Bellmer `/events/<slug>` page.
 *
 * The page renders the same `.event-detail-wrapper` card as the listing — title, time, genre,
 * lineup, poster — plus one thing the listing omits: a rich-text blurb. It spells the date
 * without a year, so it is no date source. It contributes a **description and nothing else**,
 * which is why [CrackBellmerWebsiteImporter] implements `EventImporter` directly rather than
 * extending [AbstractTwoPageWebsiteImporter][de.norm.events.scraper.AbstractTwoPageWebsiteImporter],
 * whose detail scraper is the primary source and must return a whole event (ADR-007 §"Shared
 * Detail Pages" makes the same split for Bar jeder Vernunft).
 *
 * @see CrackBellmerOverviewPageScraper for the listing parser, which supplies every other field.
 * @see <a href="https://www.crackbellmer.de/events/bad-dad-yr8lk">A Crack Bellmer event page</a>
 */
class CrackBellmerDetailPageScraper {
    /**
     * The event's blurb, or `null`. Scoped to `.event-content-wrapper` so the `.hidden-event-list`
     * below — Webflow's previous/next navigation links — stays out. Paragraphs holding only a
     * zero-width joiner are dropped: that is how the Webflow rich-text editor stores a blank spacer
     * line, and it is not whitespace, so `isNotBlank()` alone would keep it.
     */
    fun scrapeDescription(document: Document): String? =
        document
            .select(".event-content-wrapper .w-richtext p")
            .map { it.text().trim() }
            .filter { paragraph -> paragraph.any(Char::isLetterOrDigit) }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
}
