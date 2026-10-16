package de.norm.events.scraper.hau

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.textLines
import org.jsoup.nodes.Document
import java.math.BigDecimal

/** What one HAU production page adds to each of its performances. */
data class HauProduction(
    val description: String?,
    val imageUrl: String?,
    val pricePresale: BigDecimal?,
    val priceNote: String?
) {
    fun applyTo(event: ScrapedEvent): ScrapedEvent =
        event.copy(
            description = description ?: event.description,
            imageUrl = imageUrl ?: event.imageUrl,
            pricePresale = pricePresale ?: event.pricePresale,
            priceNote = priceNote ?: event.priceNote
        )
}

/**
 * Pure parser for a HAU production page (`/en/programme/pdetail/<slug>/`), which every performance
 * of the production links. The text is `.realContent`, the poster `og:image`. The price is the first
 * `.noteBox` line with a euro figure, `Price: 17–29 €, reduced 9–20 €` or a bare `12-29 €` after a
 * `Low capacity` line: the lowest full price, with the line as the note when it names more than one
 * figure; without one, a free-entry line is the note. A combi-ticket paragraph prices a festival
 * pass, not the production.
 */
class HauProductionPageScraper {
    fun scrape(document: Document): HauProduction? {
        if (document.selectFirst("#prodDetail") == null) return null
        val lines =
            document
                .select("#prodDetail .noteBox p")
                .map { it.textLines() }
                .filterNot { paragraph -> paragraph.firstOrNull()?.startsWith("Combi", ignoreCase = true) == true }
                .flatten()
        val priceLine = lines.firstOrNull { EURO_FIGURE.containsMatchIn(it) }?.replace(PRICE_LABEL, "").blankToNull()
        return HauProduction(
            description = description(document),
            imageUrl = document.attrAt("meta[property=og:image]", "content"),
            pricePresale = priceLine?.let(::lowestFullPrice),
            priceNote = priceLine?.takeIf { AMOUNT.findAll(it).count() != 1 } ?: lines.firstOrNull { hasFreeEntryPhrase(it) }.takeIf { priceLine == null }
        )
    }

    /** The production text; also read from the German page for the second language. */
    fun description(document: Document): String? = htmlParagraphText(document.selectFirst("#prodDetail .realContent")?.html())

    private fun lowestFullPrice(line: String): BigDecimal? =
        AMOUNT
            .findAll(line.substringBefore("reduced").substringBefore("ermäßigt"))
            .mapNotNull { it.value.replace(',', '.').toBigDecimalOrNull() }
            .minOrNull()

    private companion object {
        val PRICE_LABEL = Regex("""^(?:Price|Preis):\s*""", RegexOption.IGNORE_CASE)
        val EURO_FIGURE = Regex("""\d\s*€|€\s*\d""")
        val AMOUNT = Regex("""\d+(?:[.,]\d{1,2})?""")
    }
}
