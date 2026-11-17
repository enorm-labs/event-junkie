package de.norm.events.scraper.bkatheater

import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.htmlParagraphText
import org.jsoup.nodes.Document
import java.time.Duration

/** What a BKA Theater production page adds to its performances: the text, how long before the start the doors open, and free entry. */
data class BkaTheaterProduction(
    val description: String?,
    val doorsLead: Duration?,
    val free: Boolean = false
)

/**
 * Pure HTML parser for a BKA Theater production page (`/prg/<pid>`), which all performances of the
 * production share. The text is the block under the next-date box, the folded "mehr lesen"
 * part included, one line per paragraph or `<br>`; some paragraphs are bare text between `<p>`
 * elements. The ticket note under the dates says `Der Einlass beginnt 1 Stunde vor Veranstaltungsbeginn`,
 * and on a free production `Der Eintritt ist frei.` (#5237). Every note grants the companion of a
 * "B" pass holder free entry, so a paragraph about the `Begleitperson` is no free signal.
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
            description = text.joinToString("\n").replace(BLANK_RUN, "\n\n").blankToNull(),
            doorsLead = DOORS_LEAD.find(document.text())?.let(::toDuration),
            free = hasFreeEntryPhrase(ticketNote(document))
        )
    }

    /** The paragraphs under the dates table, less the companion rule. The note's `p.my-2` wraps `<p>`s, so the parser makes them siblings. */
    private fun ticketNote(document: Document): String =
        document
            .select(".bka-container-right .bka-termine ~ p")
            .map { it.text() }
            .filterNot { COMPANION.containsMatchIn(it) }
            .joinToString("\n")

    private fun toDuration(match: MatchResult): Duration {
        val amount = match.groupValues[1].toLong()
        return if (match.groupValues[2].startsWith("Stunde")) Duration.ofHours(amount) else Duration.ofMinutes(amount)
    }

    private companion object {
        val COMPANION = Regex("Begleitperson", RegexOption.IGNORE_CASE)

        val DOORS_LEAD = Regex("""Einlass beginnt (\d+) (Stunden?|Minuten) vor""")

        /** A paragraph that is only the act's social link (`instagram.com/friedemannweise`). */
        val LINK_ONLY = Regex("""^\S+\.\S+/\S*$""")

        /** The blank lines a dropped link paragraph leaves on both sides of it. */
        val BLANK_RUN = Regex("""\n{3,}""")
    }
}
