package de.norm.events.scraper.waldbuehne

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.paragraphText
import de.norm.events.scraper.parseClockPrefix
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.textAt
import org.jsoup.nodes.Document
import java.time.LocalTime

/**
 * Pure parser for a Waldbühne `/event/<slug>/` page, read for what the listing's JSON-LD lacks: the
 * support act, the doors and start clocks, the ticket shop, the promoter and the blurb.
 *
 * - The header bills the tour line as `h2` and the support apart, `<span class=event-info-support><i>Support:</i>
 * Billy Talent</span>`, so the subtitle is the bare tour line and the support goes on the bill.
 * - `dl.dl-horizontal` prints `Einlass` and `Beginn`, either a clock (`17:00 Uhr`) or `tbd`.
 * - The ticket button links the Eventim shop; a sold-out or not-yet-on-sale date shows a button with no link.
 * - `div.copy` credits the promoter, `Veranstalter <a href=…>Name</a>`, and links its site.
 * - The blurb is the region the "Beschreibung" button opens. The other regions are house rules and age limits.
 */
class WaldbuehneEventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent {
        val support = supportActs(document)
        val clocks = clocks(document)
        val promoter = document.select(CREDIT).firstOrNull { PROMOTER_LABEL.containsMatchIn(it.text()) }?.selectFirst("a")
        val promoterName = promoter?.text().blankToNull()
        return event.copy(
            subtitle = document.textAt(TOUR_LINE) ?: event.subtitle,
            description = description(document) ?: event.description,
            doorsTime = clocks[DOORS_LABEL] ?: event.doorsTime,
            startTime = clocks[START_LABEL] ?: event.startTime,
            ticketUrl = document.selectFirst(TICKET_BUTTON)?.absUrl("href")?.takeIf { it.startsWith("http") } ?: event.ticketUrl,
            soldOut = event.soldOut || document.selectFirst(SOLD_OUT_BUTTON) != null,
            promoters = listOfNotNull(promoterName).ifEmpty { event.promoters },
            promoterWebsites =
                promoterName
                    ?.let { name -> promoter?.absUrl("href")?.takeIf { it.startsWith("http") }?.let { mapOf(name to it) } }
                    ?: event.promoterWebsites,
            artists = event.artists.filter { it.role != ArtistRole.SUPPORT } + support
        )
    }

    /** The acts after `Support:` in the header, or none. */
    private fun supportActs(document: Document): List<ScrapedArtist> =
        document
            .textAt(SUPPORT_LINE)
            ?.replaceFirst(SUPPORT_LABEL, "")
            ?.let(::splitSupportActs)
            .orEmpty()
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(it, ArtistRole.SUPPORT) }

    /** Each `dt` label of the times block to the clock its `dd` prints; a `tbd` prints none. */
    private fun clocks(document: Document): Map<String, LocalTime> =
        document
            .select(TIMES)
            .mapNotNull { dt -> parseClockPrefix(dt.nextElementSibling()?.text())?.let { dt.text().trim() to it } }
            .toMap()

    private fun description(document: Document): String? =
        document
            .select(ACCORDION_BUTTON)
            .firstOrNull { it.ownText().trim().equals(DESCRIPTION_LABEL, ignoreCase = true) }
            ?.attr("aria-controls")
            ?.takeIf { it.isNotBlank() }
            ?.let { document.getElementById(it) }
            ?.select("p")
            ?.paragraphText()

    private companion object {
        const val TOUR_LINE = ".head h2"
        const val SUPPORT_LINE = ".head .event-info-support"
        const val TIMES = "dl.dl-horizontal dt"
        const val DOORS_LABEL = "Einlass"
        const val START_LABEL = "Beginn"
        const val TICKET_BUTTON = "a.big-btn.ticket[href]"
        const val SOLD_OUT_BUTTON = "a.big-btn.sold"
        const val CREDIT = "div.copy"
        const val ACCORDION_BUTTON = "button.acc-button[aria-controls]"
        const val DESCRIPTION_LABEL = "Beschreibung"

        val SUPPORT_LABEL = Regex("""^\s*support\s*:\s*""", RegexOption.IGNORE_CASE)
        val PROMOTER_LABEL = Regex("""^\s*Veranstalter""", RegexOption.IGNORE_CASE)
    }
}
