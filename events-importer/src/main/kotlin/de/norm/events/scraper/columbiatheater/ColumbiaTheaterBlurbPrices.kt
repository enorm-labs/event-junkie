package de.norm.events.scraper.columbiatheater

import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.parseLabelledPrices
import java.math.BigDecimal

/** The prices a Columbia Theater blurb names, and the [note] that keeps a fee or `ab` qualifier. */
internal data class ColumbiaTheaterBlurbPrices(
    val presale: BigDecimal? = null,
    val boxOffice: BigDecimal? = null,
    val note: String? = null
)

/**
 * The prices on the [blurb]'s price lines (#4933). Most pages print none. Some print one line that
 * opens with its label: `Ticketpreis 36,- Euro zzgl. Gebühren`, `GA Ticket: € 45,00 plus sales fees`,
 * `Abendkasse: 40,00 €`. Only such a line counts, so a date, a year or an age limit in the prose
 * is never read as a price. `Vorverkauf`/`AK` pairs go through [parseLabelledPrices]; a ticket label
 * it does not know (`Ticketpreis`, `GA Ticket`, `Eintritt`) is the presale price, its lowest amount
 * before a fee or a box-office label. The first line of each kind wins. The price lines are the
 * note when one says more than the figure: a fee, an `ab` (from) qualifier or a second amount.
 */
internal fun parseColumbiaTheaterBlurbPrices(blurb: String?): ColumbiaTheaterBlurbPrices {
    val priceLines =
        blurb
            .orEmpty()
            .lines()
            .map(String::trim)
            .mapNotNull { line -> priceLine(line)?.let { line to it } }
    val qualified = priceLines.any { (line, _) -> PRICE_QUALIFIER.containsMatchIn(line) || euroAmounts(line).size > 1 }
    return ColumbiaTheaterBlurbPrices(
        presale = priceLines.firstNotNullOfOrNull { it.second.presale },
        boxOffice = priceLines.firstNotNullOfOrNull { it.second.boxOffice },
        note = priceLines.joinToString(" · ") { it.first }.takeIf { qualified }
    )
}

/** The prices of one blurb [line] that opens with a price label, or `null` when it is no price line. */
private fun priceLine(line: String): ColumbiaTheaterBlurbPrices? {
    val label = PRICE_LINE_LABEL.find(line) ?: return null
    val labelled = parseLabelledPrices(line)
    val ticketPrice =
        label.groups[TICKET_GROUP]?.let {
            euroAmounts(line.substring(label.range.last + 1).split(UNLABELLED_PRICE_END, limit = 2).first()).minOrNull()
        }
    return ColumbiaTheaterBlurbPrices(labelled.presale ?: ticketPrice, labelled.boxOffice)
        .takeIf { it.presale != null || it.boxOffice != null }
}

private const val TICKET_GROUP = "ticket"

/** A line that opens with a price label: a known presale or box-office word, or a ticket word ([TICKET_GROUP]). */
private val PRICE_LINE_LABEL =
    Regex(
        """^(?:vorverkauf|vvk|presale|abendkasse|tageskasse|ak|box\s*office|(?<$TICKET_GROUP>(?:ga\s+)?tickets?(?:preise?)?|eintritt|preis))(?!\p{L})""",
        RegexOption.IGNORE_CASE
    )

/** Where a ticket label's own amount ends: a fee clause or a box-office label after it. */
private val UNLABELLED_PRICE_END =
    Regex("""(?<!\p{L})(?:zzgl|plus|inkl|abendkasse|tageskasse|ak|box\s*office)(?!\p{L})|\+""", RegexOption.IGNORE_CASE)

/** What a price line says beyond its figure: a booking fee or an `ab` (from) amount. */
private val PRICE_QUALIFIER = Regex("""geb(?:ü|ue)hr|fee|(?<!\p{L})ab\s*:?\s*(?:€\s*)?\d""", RegexOption.IGNORE_CASE)
