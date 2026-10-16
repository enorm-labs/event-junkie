package de.norm.events.scraper.bkatheater

import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.htmlParagraphText
import org.jsoup.nodes.Document
import java.time.Duration

/** What a BKA Theater production page adds to its performances: the text and how long before the start the doors open. */
data class BkaTheaterProduction(
    val description: String?,
    val doorsLead: Duration?
)

/**
 * Pure HTML parser for a BKA Theater production page (`/prg/<pid>`), which all performances of the
 * production share. The text is the block under the next-date box, the folded "mehr lesen"
 * part included, one line per paragraph or `<br>`; some paragraphs are bare text between `<p>`
 * elements. The ticket note under the dates says `Der Einlass beginnt 1 Stunde vor Veranstaltungsbeginn`.
 */
class BkaTheaterProductionPageScraper {
    fun scrape(document: Document): BkaTheaterProduction {
        val body = document.selectFirst(".bka-container-right .bka-termin-box ~ div")?.clone()
        body?.select(".accordionHeader")?.remove()
        val text =
            htmlParagraphText(body?.html())
                ?.lines()
                ?.filterNot { LINK_ONLY.matches(it) }
                .orEmpty()
        return BkaTheaterProduction(
            description = text.joinToString("\n").blankToNull(),
            doorsLead = DOORS_LEAD.find(document.text())?.let(::toDuration)
        )
    }

    private fun toDuration(match: MatchResult): Duration {
        val amount = match.groupValues[1].toLong()
        return if (match.groupValues[2].startsWith("Stunde")) Duration.ofHours(amount) else Duration.ofMinutes(amount)
    }

    private companion object {
        val DOORS_LEAD = Regex("""Einlass beginnt (\d+) (Stunden?|Minuten) vor""")

        /** A paragraph that is only the act's social link (`instagram.com/friedemannweise`). */
        val LINK_ONLY = Regex("""^\S+\.\S+/\S*$""")
    }
}
