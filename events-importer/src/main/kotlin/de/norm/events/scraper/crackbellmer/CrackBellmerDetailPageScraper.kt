package de.norm.events.scraper.crackbellmer

import de.norm.events.scraper.labelledClock
import org.jsoup.nodes.Document
import java.time.LocalTime

/**
 * Pure HTML parser for a Crack Bellmer `/events/<slug>` page.
 *
 * The page renders the same `.event-detail-wrapper` card as the listing — title, time, genre,
 * lineup, poster — plus one thing the listing omits: a rich-text blurb. It spells the date
 * without a year, so it is no date source. It contributes the **blurb and the doors line in it**,
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
    fun scrapeDescription(document: Document): String? = blurbParagraphs(document).joinToString("\n").takeIf { it.isNotBlank() }

    /**
     * The doors time from the first blurb paragraph that prints one (`Door opens at 19:00`,
     * `Doors open at 3 pm`, `Doors 19:00`), with the free-entry clause of that paragraph, or `null`
     * when no paragraph does. Most nights print no doors line.
     */
    fun scrapeDoors(document: Document): DoorsLine? =
        blurbParagraphs(document).firstNotNullOfOrNull { paragraph ->
            labelledClock(paragraph, DOORS_LABEL)?.let { DoorsLine(it, FREE_ENTRY_CLAUSE.find(paragraph)?.value) }
        }

    private fun blurbParagraphs(document: Document): List<String> =
        document
            .select(".event-content-wrapper .w-richtext p")
            .map { it.text().trim() }
            .filter { paragraph -> paragraph.any(Char::isLetterOrDigit) }

    /** A blurb's doors line: the [time] and the [freeEntryNote] beside it (`Free entry until 22:00`). */
    data class DoorsLine(
        val time: LocalTime,
        val freeEntryNote: String?
    )

    private companion object {
        /**
         * "Door", "Doors", "Door opens", "Doors open". Not after "the": `Tickets at the door from
         * 10pm` says when the ticket sale starts, not when the doors open.
         */
        const val DOORS_LABEL = """(?<!the\s)doors?(?:\s+open)?"""

        /** Free entry for the early guests: `Free entry until 22:00`, `free entry 'til 10 PM`, `FREE ENTRY UNTILL 22:00`. */
        val FREE_ENTRY_CLAUSE =
            Regex(
                """\bfree\s+entry\s+(?:until+|till?|'til)\s+(?:\d{1,2}(?:[:.]\d{2})?(?:\s*[ap]\.?m\.?(?!\p{L}))?|midnight)""",
                RegexOption.IGNORE_CASE
            )
    }
}
