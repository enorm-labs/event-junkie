package de.norm.events.scraper

import org.jsoup.select.Elements
import java.math.BigDecimal

// Shared price parsing: euro amounts, presale and box-office labels, and the
// Kulturhäuser platform's `.price` blocks (Astra, Lido).

/**
 * Splits Kulturhäuser-platform `.price` blocks into presale and box-office prices.
 *
 * Each `.price` carries a `.price__value` (e.g. "39,90€", "35.20€", or
 * "30,00&nbsp;€" with a non-breaking space) and a `.price__label`. A box-office
 * label ([isBoxOfficeLabel]) maps to the box-office price; everything else
 * (Vorverkauf / "VVK …") maps to presale. The first value
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
        if (isBoxOfficeLabel(price.selectFirst(".price__label")?.text())) {
            boxOffice = boxOffice ?: value
        } else {
            presale = presale ?: value
        }
    }

    return presale to boxOffice
}

/**
 * Parses the first euro amount in [text] ([euroAmounts]); `null` when there is none.
 */
fun parsePriceValue(text: String?): BigDecimal? = euroAmounts(text).firstOrNull()

/**
 * Every euro amount in [text], in reading order. The sign may lead (`€15`) or trail (`15 €`,
 * `15,90€`, `15,- €`), and a trailing currency may be spelled out (`13,00 EUR`, `32 Euro`). A
 * regular or non-breaking space may separate sign and amount. An amount that runs straight into a
 * digit or a colon is refused, since that is how a clock time looks once markup is flattened
 * (`€5` + `20:00`).
 */
fun euroAmounts(text: String?): List<BigDecimal> =
    EURO_AMOUNT
        .findAll(text.orEmpty())
        .mapNotNull { match ->
            match.groupValues
                .drop(1)
                .first { it.isNotEmpty() }
                .removeSuffix(",-")
                .replace(',', '.')
                .toBigDecimalOrNull()
        }.toList()

/** Whether [label] names the box office: `Abendkasse`, `Tageskasse`, `AK`, `at the door`, `box office`. */
fun isBoxOfficeLabel(label: String?): Boolean = label != null && BOX_OFFICE_LABEL.containsMatchIn(label)

/**
 * The presale and box-office amounts that [text] names after a price label, as in
 * `VVK: 28 € (zzgl. Gebühr) / AK: 32 €` or `Vorverkauf 12 €/ 18 €/ 25 € * Abendkasse 30 €`. Each
 * label owns the text up to the next label or concession marker (`ermäßigt`, `reduced`, …). Its
 * lowest amount is the price, so a tiered door or presale stores its cheapest tier
 * (docs/DATA_MODEL.md). The first label of each kind that names an amount wins. An amount before
 * any label is not assigned. [LabelledPrices.fromPrice] tells whether an `ab` (from) qualifier
 * precedes a labelled amount.
 */
fun parseLabelledPrices(text: String?): LabelledPrices {
    val source = text.orEmpty()
    val markers = PRICE_SEGMENT_MARKER.findAll(source).toList()
    var presale: BigDecimal? = null
    var boxOffice: BigDecimal? = null
    var fromPrice = false
    markers.forEachIndexed { index, marker ->
        val segment = source.substring(marker.range.last + 1, markers.getOrNull(index + 1)?.range?.first ?: source.length)
        val lowest = euroAmounts(segment).minOrNull() ?: return@forEachIndexed
        when {
            marker.groups[PRESALE_GROUP] != null -> presale = presale ?: lowest
            marker.groups[BOX_OFFICE_GROUP] != null -> boxOffice = boxOffice ?: lowest
            else -> return@forEachIndexed
        }
        fromPrice = fromPrice || FROM_PRICE.containsMatchIn(segment)
    }
    return LabelledPrices(presale, boxOffice, fromPrice)
}

/** The prices [parseLabelledPrices] reads; [fromPrice] marks an `ab` (from) amount, the cheapest of several tickets. */
data class LabelledPrices(
    val presale: BigDecimal? = null,
    val boxOffice: BigDecimal? = null,
    val fromPrice: Boolean = false
)

/** The words that name a presale price. */
private const val PRESALE_LABELS = """vorverkauf|vvk|pre-?sale|advance"""

/**
 * The words that name a box-office price. `ak` is only ever a whole word, so `VVK` cannot match on
 * its letters. A bare `door` is left out: `Doors 19:00` is a time.
 */
private const val BOX_OFFICE_LABELS = """abendkasse|tageskasse|ak|box\s*office|at\s+the\s+door|door\s+price"""

/** A concession or member rate after a label, which is not the label's price. */
private const val CONCESSION_LABELS = """erm(?:ä|ae)(?:ss|ß)igt|erm\.|reduced|concessions?|studierende|sch(?:ü|ue)ler|mitglieder"""

private const val PRESALE_GROUP = "presale"
private const val BOX_OFFICE_GROUP = "boxoffice"

private val BOX_OFFICE_LABEL = Regex("""(?<!\p{L})(?:$BOX_OFFICE_LABELS)(?!\p{L})""", RegexOption.IGNORE_CASE)

/** A label that opens a price segment in [parseLabelledPrices]; a concession marker only closes the one before. */
private val PRICE_SEGMENT_MARKER =
    Regex(
        """(?<!\p{L})(?:(?<$PRESALE_GROUP>$PRESALE_LABELS)|(?<$BOX_OFFICE_GROUP>$BOX_OFFICE_LABELS)|$CONCESSION_LABELS)(?!\p{L})""",
        RegexOption.IGNORE_CASE
    )

/** An `ab` (from) qualifier directly before an amount: `ab 74,99 €`, `ab: 69,99 €`, `Ab €20`. */
private val FROM_PRICE = Regex("""(?<!\p{L})ab\s*:?\s*(?:€\s*)?\d""", RegexOption.IGNORE_CASE)

/** One amount: `€`-led, or trailed by `€`, `EUR` or `Euro`; German `15,-` included. */
private val EURO_AMOUNT =
    Regex(
        """€[\s\u00a0]*(\d+(?:[.,]\d{1,2})?)(?![\d:]|[.,]\d)|(?<![\d.,])(\d+(?:[.,]\d{1,2}|,-)?)[\s\u00a0]*(?:€|eur(?:o|os)?(?!\p{L}))""",
        RegexOption.IGNORE_CASE
    )
