package de.norm.events.scraper.pfefferbergtheater

import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.htmlParagraphText
import org.jsoup.nodes.Document

/** What a Pfefferberg Theater production page adds to its performances. */
data class PfefferbergTheaterProduction(
    val description: String?,
    val imageUrl: String?,
    val ticketUrl: String?
)

/**
 * Pure HTML parser for a production page (`/spielplan/kalender/<slug>/`), which all its dates share. The
 * text is the first `.richtext` under `main`, often German then an `ENGLISH VERSION`. The JSON-LD is not
 * read: it cuts the text short, and a text with a line break makes it unparseable.
 */
class PfefferbergTheaterProductionPageScraper {
    fun scrape(document: Document): PfefferbergTheaterProduction =
        PfefferbergTheaterProduction(
            description = htmlParagraphText(document.selectFirst("main .richtext")?.html()),
            imageUrl = document.selectFirst("main .slickgal img[data-lazy]")?.absUrl("data-lazy")?.blankToNull(),
            ticketUrl = document.selectFirst("main a.button[href*=billeto.net]")?.absUrl("href")?.blankToNull()
        )
}
