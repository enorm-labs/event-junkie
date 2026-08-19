package de.norm.events.scraper.delphi

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.textAt
import org.jsoup.nodes.Document

/**
 * What a production page adds to every performance of that production.
 *
 * @property description the full production blurb, several paragraphs where the programme row
 *   carries only a one-sentence teaser.
 * @property imageUrl the full-width production photo, where the row carries a list thumbnail.
 */
data class DelphiProduction(
    val description: String?,
    val imageUrl: String?
) {
    /**
     * Applies this production's data to one of its performance dates.
     *
     * The production page wins on both fields it states — its blurb supersedes the teaser and its
     * photo the thumbnail — and a field it leaves empty keeps whatever the programme row had.
     */
    fun applyTo(event: ScrapedEvent): ScrapedEvent =
        event.copy(
            description = description ?: event.description,
            imageUrl = imageUrl ?: event.imageUrl
        )
}

/**
 * Pure HTML parser for a Theater im Delphi production page (`/programm/?prod=<id>`).
 *
 * The page belongs to a **production**, not a date: it lists every performance of the run in a
 * `table.program_table_schmal` and describes the show once. Only the two things the programme row
 * cannot carry are read from it — the full blurb and the full-width photo. Everything else
 * (identity, date, clock, type, price, ticket link) is already settled by the programme page, which
 * states it per performance.
 *
 * @see DelphiProgrammePageScraper for the programme parser.
 * @see DelphiWebsiteImporter for the HTTP fetch orchestrator.
 */
class DelphiProductionPageScraper {
    /**
     * Parses a production page, or `null` when it carries no production heading — the marker that
     * the request did not return a real production page.
     */
    fun scrape(document: Document): DelphiProduction? {
        document.textAt(".single_event h2.title") ?: return null

        return DelphiProduction(
            description = document.textAt(".productionText"),
            imageUrl = document.imgSrcAt(".profile_img img")
        )
    }
}
