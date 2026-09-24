package de.norm.events.scraper

import org.jsoup.select.Elements
import java.math.BigDecimal

// Shared price parsing for venues on the Kulturhäuser platform (Astra, Lido),
// which render prices in identical `.price` blocks. The container differs by
// theme (Astra `.prices`, Lido `.event-purchase__prices`), so callers pass the
// already-scoped `.price` elements while this module owns the per-price
// classification and value parsing.

/**
 * Splits Kulturhäuser-platform `.price` blocks into presale and box-office prices.
 *
 * Each `.price` carries a `.price__value` (e.g. "39,90€", "35.20€", or
 * "30,00&nbsp;€" with a non-breaking space) and a `.price__label`. Labels
 * containing "Abendkasse" or the standalone "AK" token map to the box-office
 * price; everything else (Vorverkauf / "VVK …") maps to presale. The first value
 * seen for each category wins, so the duplicate price blocks the markup renders
 * for mobile/desktop collapse to a single value.
 *
 * @param priceElements the venue-scoped `.price` elements, e.g.
 *   `content.select(".prices .price")` (Astra) or `content.select(".price")` (Lido).
 * @return a pair of (presale, boxOffice), either of which may be `null`.
 */
fun parsePresaleAndBoxOfficePrices(priceElements: Elements): Pair<BigDecimal?, BigDecimal?> {
    var presale: BigDecimal? = null
    var boxOffice: BigDecimal? = null

    for (price in priceElements) {
        val value = parsePriceValue(price.selectFirst(".price__value")?.text()) ?: continue
        val label =
            price
                .selectFirst(".price__label")
                ?.text()
                ?.lowercase()
                .orEmpty()
        val isBoxOffice = label.contains("abendkasse") || AK_LABEL_PATTERN.containsMatchIn(label)
        if (isBoxOffice) {
            boxOffice = boxOffice ?: value
        } else {
            presale = presale ?: value
        }
    }

    return presale to boxOffice
}

/**
 * Parses the first monetary value from a price string, accepting both German
 * (`39,90€`) and dot (`35.20€`) decimal separators, and tolerating a regular or
 * non-breaking space before the `€`. Returns `null` when no value is found.
 */
@Suppress("ReturnCount") // Guard clauses for blank input and unparseable value are clearer than nesting
fun parsePriceValue(text: String?): BigDecimal? {
    if (text.isNullOrBlank()) return null
    val match = PRICE_PATTERN.find(text) ?: return null
    return try {
        BigDecimal(match.groupValues[1].replace(",", "."))
    } catch (_: NumberFormatException) {
        null
    }
}

/**
 * Parses the first price whose currency is spelled out, as in "69,90 EUR" (Gambio shops, Wuhlheide).
 * The `€`-anchored [parsePriceValue] misses that spelling. Returns `null` when no value is found.
 */
fun parseEurCodePrice(text: String?): BigDecimal? =
    EUR_CODE_PRICE_PATTERN
        .find(text.orEmpty())
        ?.groupValues
        ?.get(1)
        ?.replace(',', '.')
        ?.toBigDecimalOrNull()

/**
 * Matches a price value with an optional decimal part, e.g. "39,90€", "35.20€",
 * "53€", or "30,00&nbsp;€". The character class also accepts a non-breaking
 * space, which some themes (Lido) place between the amount and the euro sign.
 */
private val PRICE_PATTERN = Regex("""(\d+(?:[.,]\d{1,2})?)[\s\u00a0]*€""")

/** Matches the standalone "AK" (Abendkasse) token, so labels like "VVK" don't false-match on the letters. */
private val AK_LABEL_PATTERN = Regex("""\bak\b""")

/** A price value followed by the spelled-out currency code, e.g. "13,00 EUR". */
private val EUR_CODE_PRICE_PATTERN = Regex("""(\d+(?:[.,]\d{1,2})?)\s*EUR""", RegexOption.IGNORE_CASE)
