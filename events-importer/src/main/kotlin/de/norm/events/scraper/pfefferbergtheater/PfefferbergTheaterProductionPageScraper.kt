package de.norm.events.scraper.pfefferbergtheater

import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.htmlParagraphText
import org.jsoup.nodes.Document

/** What a Pfefferberg Theater production page adds to its performances. */
data class PfefferbergTheaterProduction(
    val description: String?,
    val imageUrl: String?,
    val ticketUrl: String?,
    /** The audience line when it names a family or a school show, which EVENT_SCOPE.md §3.5 marks `for-children`. */
    val childrensAudience: String?
)

/**
 * Pure HTML parser for a production page (`/spielplan/kalender/<slug>/`), which all its dates share. The
 * text is the first `.richtext` under `main`, often German then an `ENGLISH VERSION`. The JSON-LD is not
 * read: it cuts the text short, and a text with a line break makes it unparseable. A Märchen im Glaspalast page
 * states its audience in one line of the text: `Märchen für ALLE – Zutritt ab 4 Jahren`, `Schulvorstellung ab 4
 * Jahren`, `Nur für Erwachsene – Zutritt ab 14 Jahren`. The words decide, not the age: an adults-only comical is
 * `Zutritt ab 12 Jahren`.
 */
class PfefferbergTheaterProductionPageScraper {
    fun scrape(document: Document): PfefferbergTheaterProduction {
        val description = htmlParagraphText(document.selectFirst("main .richtext")?.html())
        return PfefferbergTheaterProduction(
            description = description,
            imageUrl = document.selectFirst("main .slickgal img[data-lazy]")?.absUrl("data-lazy")?.blankToNull(),
            ticketUrl = document.selectFirst("main a.button[href*=billeto.net]")?.absUrl("href")?.blankToNull(),
            // The house sets a no-break space inside the line (`Schulvorstellung\u00a0ab 4 Jahren`).
            childrensAudience =
                description
                    ?.lines()
                    ?.map { it.replace('\u00a0', ' ') }
                    ?.firstOrNull { AUDIENCE.containsMatchIn(it) }
                    ?.takeIf { CHILDRENS_AUDIENCE.containsMatchIn(it) }
        )
    }

    private companion object {
        val AUDIENCE = Regex("""(?:Zutritt|Schulvorstellung)\s+ab\s+\d+\s+Jahren""")
        val CHILDRENS_AUDIENCE = Regex("""für alle|Schulvorstellung""", RegexOption.IGNORE_CASE)
    }
}
