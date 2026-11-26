package de.norm.events.scraper

import org.jsoup.select.Elements
import java.math.BigDecimal

// Shared price parsing: euro amounts, presale and box-office labels, and the
// Kulturhäuser platform's `.price` blocks (Astra, Lido).

/**
 * Splits Kulturhäuser-platform `.price` blocks into presale and box-office prices.
 *
 * Each `.price` carries a `.price__value` (e.g. "39,90€", "35.20€", or
 * "30,00&nbsp;€" with a non-breaking space) and a `.price__label`. A concession label
 * ([PLATFORM_CONCESSION_LABEL]: `U25`, `ermäßigt`, `Schüler`, …) maps to neither column (#5551). A box-office
 * label ([isBoxOfficeLabel]) maps to the box-office price; everything else
 * (Vorverkauf / "VVK …") maps to presale. The first value
 * seen for each category wins, so the duplicate price blocks the markup renders
 * for mobile/desktop collapse to a single value.
 *
 * @param priceElements the venue-scoped `.price` elements, e.g.
 *   `content.select(".prices .price")` (Astra) or `content.select(".price")` (Lido).
 * @return the presale and box-office prices, either of which may be `null`, and a note.
 */
fun parsePresaleAndBoxOfficePrices(priceElements: Elements): PlatformPrices {
    var presale: BigDecimal? = null
    var boxOffice: BigDecimal? = null
    var concession = false
    val tiers = mutableListOf<String>()

    for (price in priceElements) {
        val valueText = price.textAt(".price__value")
        val label = price.textAt(".price__label")
        val value = parsePriceValue(valueText) ?: continue
        tiers += listOfNotNull(label, valueText).joinToString(": ")
        when {
            label != null && PLATFORM_CONCESSION_LABEL.containsMatchIn(label) -> concession = true
            isBoxOfficeLabel(label) -> boxOffice = boxOffice ?: value
            else -> presale = presale ?: value
        }
    }

    val note =
        tiers
            .distinct()
            .joinToString(" · ")
            .takeIf { concession }
    return PlatformPrices(presale, boxOffice, note)
}

/**
 * What [parsePresaleAndBoxOfficePrices] reads. [note] lists every priced tier as `label: value` when one of them is a
 * concession, so the tier the columns leave out is kept. A tier with no amount, such as a presale start date, is left out.
 */
data class PlatformPrices(
    val presale: BigDecimal? = null,
    val boxOffice: BigDecimal? = null,
    val note: String? = null
)

/**
 * Parses the first euro amount in [text] ([euroAmounts]); `null` when there is none.
 */
fun parsePriceValue(text: String?): BigDecimal? = euroAmounts(text).firstOrNull()

/**
 * Every euro amount in [text], in reading order. The sign may lead (`€15`) or trail (`15 €`,
 * `15,90€`, `15,- €`), and a trailing currency may be spelled out (`13,00 EUR`, `32 Euro`). A
 * regular, non-breaking, narrow no-break or thin space may separate sign and amount. An amount that runs straight into a
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
                .germanDecimalOrNull()
        }.toList()

/** A number with an optional German comma or a dot and one or two decimals, no currency: `18`, `15,00`, `9.5`. */
val GERMAN_DECIMAL = Regex("""\d+(?:[.,]\d{1,2})?""")

/** Every [GERMAN_DECIMAL] in [text], in reading order, for a cell that prices without a `€` ([euroAmounts] needs one). */
fun germanDecimals(text: String): List<BigDecimal> = GERMAN_DECIMAL.findAll(text).mapNotNull { it.value.germanDecimalOrNull() }.toList()

/** This number with a German decimal comma read as a point (`15,90`), or `null` when it is not a number. */
fun String.germanDecimalOrNull(): BigDecimal? = replace(',', '.').toBigDecimalOrNull()

/** Whether [label] names the box office: `Abendkasse`, `Tageskasse`, `AK`, `at the door`, `box office`. */
fun isBoxOfficeLabel(label: String?): Boolean = label != null && BOX_OFFICE_LABEL.containsMatchIn(label)

/**
 * The presale and box-office amounts that [text] names after a price label, as in
 * `VVK: 28 € (zzgl. Gebühr) / AK: 32 €` or `Vorverkauf 12 €/ 18 €/ 25 € * Abendkasse 30 €`. Each
 * label owns the text up to the next label or concession marker (`ermäßigt`, `reduced`, …). Its
 * lowest amount is the price, so a tiered door or presale stores its cheapest tier
 * (docs/DATA_MODEL.md). A booking fee added to it ([FEE_CLAUSE]) is no tier: `VVK 28 € zzgl. 2,50 € Gebühr`
 * is 28 €. The first label of each kind that names an amount wins. An amount before any label is not assigned.
 * [LabelledPrices.fromPrice] tells whether an `ab` (from) qualifier precedes a labelled amount.
 */
fun parseLabelledPrices(text: String?): LabelledPrices {
    val source = text.orEmpty()
    val markers = PRICE_SEGMENT_MARKER.findAll(source).toList()
    var presale: BigDecimal? = null
    var boxOffice: BigDecimal? = null
    var fromPrice = false
    markers.forEachIndexed { index, marker ->
        val segment = source.substring(marker.range.last + 1, markers.getOrNull(index + 1)?.range?.first ?: source.length)
        val lowest = euroAmounts(segment.replace(FEE_CLAUSE, " ")).minOrNull() ?: return@forEachIndexed
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
internal const val CONCESSION_LABELS = """erm(?:ä|ae)(?:ss|ß)igt|erm\.|reduced|concessions?|studierende|sch(?:ü|ue)ler|mitglieder"""

/** A Kulturhäuser `.price__label` naming a concession: the shared [CONCESSION_LABELS], an age limit `U25`, `Student`. */
private val PLATFORM_CONCESSION_LABEL =
    Regex("""(?<!\p{L})(?:$CONCESSION_LABELS|u\s?\d{2}|student(?:en|s)?)(?![\p{L}\d])""", RegexOption.IGNORE_CASE)

private const val PRESALE_GROUP = "presale"
private const val BOX_OFFICE_GROUP = "boxoffice"

private val BOX_OFFICE_LABEL = Regex("""(?<!\p{L})(?:$BOX_OFFICE_LABELS)(?!\p{L})""", RegexOption.IGNORE_CASE)

/** A label that opens a price segment in [parseLabelledPrices]; a concession marker only closes the one before. */
private val PRICE_SEGMENT_MARKER =
    Regex(
        """(?<!\p{L})(?:(?<$PRESALE_GROUP>$PRESALE_LABELS)|(?<$BOX_OFFICE_GROUP>$BOX_OFFICE_LABELS)|$CONCESSION_LABELS)(?!\p{L})""",
        RegexOption.IGNORE_CASE
    )

/**
 * A fee added to a price: `zzgl. 2,50 € Gebühr`, `+0,45 € Ticket-Servicegebühr`, `plus 1,50 € fee`. The fee word is
 * required, so `12 € + 15 €` stays two tiers.
 */
private val FEE_CLAUSE =
    Regex(
        """(?:zzgl\.?|plus|\+)\s*(?:ca\.\s*)?(?:€\s*)?\d+(?:[.,]\d{1,2}|,-)?\s*(?:€|eur(?:o|os)?)?\s*[\p{L}-]*(?:geb(?:ü|ue)hr|fee|charge)\p{L}*""",
        RegexOption.IGNORE_CASE
    )

/** An `ab` (from) qualifier directly before an amount: `ab 74,99 €`, `ab: 69,99 €`, `Ab €20`. */
private val FROM_PRICE = Regex("""(?<!\p{L})ab\s*:?\s*(?:€\s*)?\d""", RegexOption.IGNORE_CASE)

/** A space between sign and amount: `\s` misses the no-break (U+00A0), narrow no-break (U+202F) and thin (U+2009) ones. */
private const val AMOUNT_SPACE = """[\s\u00a0\u202f\u2009]"""

/** One amount: `€`-led, or trailed by `€`, `EUR` or `Euro`; German `15,-` included. */
private val EURO_AMOUNT =
    Regex(
        """€$AMOUNT_SPACE*(\d+(?:[.,]\d{1,2})?)(?![\d:]|[.,]\d)|(?<![\d.,])(\d+(?:[.,]\d{1,2}|,-)?)$AMOUNT_SPACE*(?:€|eur(?:o|os)?(?!\p{L}))""",
        RegexOption.IGNORE_CASE
    )
