package de.norm.events.scraper.galilaeakirche

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.LabelledPrices
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.parseLabelledPrices
import org.jsoup.nodes.Document

/**
 * Pure parser for one Galiläakirche event page: the plugin's custom fields `vsel-acf-*`, each a name and a value. `doors`
 * holds `Einlass: 19:30 Uhr`, `vorverkauf_preis` and `eintrittspreis` the prices as prose, `vorverkauf` the ticket link,
 * and `beschreibung` and `band_1`, `band_2` the event text. The `band_*` fields hold prose or a video, not a line-up.
 *
 * A price note keeps the venue's words where the labelled amounts do not tell the whole price: a social or solidarity
 * tier in brackets, or a text with no labelled amount. The bracketed tiers do not count towards the amounts. A table of
 * tiers under one `VVK//AK` label pairs each presale amount with a door amount, so it stays a note without amounts.
 */
class GalilaeakircheEventPageScraper {
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val fields = document.selectFirst("main .vsel-content") ?: return null
        val title = document.selectFirst("main h1.entry-title")?.text().blankToNull() ?: return null
        val priceText = fields.select(PRICE_FIELDS).joinToString(" / ") { it.text() }.blankToNull()
        val prices =
            if (priceText != null &&
                PAIRED_LABELS.containsMatchIn(priceText)
            ) {
                LabelledPrices()
            } else {
                parseLabelledPrices(priceText?.replace(BRACKETED, " "))
            }
        return ScrapedEvent(
            title = title,
            description = description(document),
            eventDate = UNRESOLVED_EVENT_DATE,
            doorsTime = labelledClock(fields.selectFirst(".vsel-acf-doors .acf-field-value")?.text(), DOORS_LABELS),
            pricePresale = prices.presale,
            priceBoxOffice = prices.boxOffice,
            priceNote = priceText?.takeIf { '(' in it || (prices.presale == null && prices.boxOffice == null) },
            ticketUrl = fields.selectFirst(".vsel-acf-vorverkauf a[href]")?.absUrl("href").blankToNull(),
            sourceUrl = sourceUrl,
            sourceId = galilaeakircheSourceId(sourceUrl)
        )
    }

    private fun description(document: Document): String? =
        document
            .select("main .vsel-content .vsel-acf-beschreibung .acf-field-value, main .vsel-content div[class^=vsel-acf-band_] .acf-field-value")
            .mapNotNull { htmlParagraphText(it.html()) }
            .joinToString("\n")
            .blankToNull()

    private companion object {
        const val PRICE_FIELDS = ".vsel-acf-vorverkauf_preis .acf-field-value, .vsel-acf-eintrittspreis .acf-field-value"

        val PAIRED_LABELS = Regex("""\b(?:vvk|vorverkauf)\s*/+\s*(?:ak|abendkasse)\b""", RegexOption.IGNORE_CASE)

        val BRACKETED = Regex("""\([^)]*\)""")
    }
}
