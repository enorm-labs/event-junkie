package de.norm.events.scraper.downstairs

import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaOffers
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import org.jsoup.nodes.Document
import java.math.BigDecimal
import java.time.LocalTime

/** What a performance's ticket page adds to its list row. */
data class DownstairsShow(
    val description: String?,
    val imageUrl: String?,
    val price: BigDecimal?,
    val priceNote: String?,
    val doorsTime: LocalTime?
)

/**
 * Pure parser for a Downstairs ticket page, read through its schema.org `Event` JSON-LD. The
 * `name` repeats venue and date and the `endDate` is the shop's 6 a.m. default, so neither is read.
 *
 * The doors come from the `Einlass: 19:30 Uhr / Beginn: 20:00 Uhr` badge. The price is the lowest
 * offer. The tier labels (`Normalpreis`, `Social Club`) are only in the variant forms, which a
 * sold-out page does not render, so a sold-out show has no tier note.
 *
 * A show sold elsewhere redirects to that seller (rausgegangen, fienta). Its page is not the shop's,
 * so a page without the shop's info badges is not read: fienta's JSON-LD prices a ticket at 0.00.
 */
class DownstairsTicketPageScraper {
    fun scrape(document: Document): DownstairsShow? =
        document.jsonLdEvents().firstOrNull()?.takeIf { document.selectFirst(INFO_BADGE) != null }?.let { event ->
            DownstairsShow(
                description = htmlParagraphText(event.stringOrNull("description")),
                imageUrl = event.schemaImageUrl(),
                price = event.schemaOffers().mapNotNull { it.stringOrNull("price")?.toBigDecimalOrNull() }.minOrNull(),
                priceNote = tierNote(document),
                doorsTime = document.select(INFO_BADGE).firstNotNullOfOrNull { DOORS.find(it.text()) }?.let { parseTime(it.groupValues[1]) }
            )
        }

    /** `Normalpreis: 27,50 € · Social Club: 15,50 €`, when the page sells more than one tier. */
    private fun tierNote(document: Document): String? =
        document
            .select(".variant-form")
            .mapNotNull { form ->
                val label = form.textAt(".variant-form-body .col > .row > .col")?.let(::spaced) ?: return@mapNotNull null
                val price = form.textAt(".variant-form-body .col > .row > .col-auto")?.let(::spaced) ?: return@mapNotNull null
                "$label: $price"
            }.takeIf { it.size > 1 }
            ?.joinToString(" · ")

    private fun spaced(text: String) = text.replace(SPACES, " ").trim()

    private companion object {
        const val INFO_BADGE = ".ds-info-badge"
        val DOORS = Regex("""Einlass:\s*(\d{1,2}:\d{2})""")
        val SPACES = Regex("""[\s\u00A0\u202F]+""")
    }
}
