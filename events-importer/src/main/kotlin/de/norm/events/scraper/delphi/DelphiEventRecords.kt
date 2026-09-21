package de.norm.events.scraper.delphi

import de.norm.events.scraper.BERLIN
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * The price and free-entry facts for one performance, recovered from the programme page's
 * **leaked record dump**.
 *
 * The calendar section emits a `var_dump()` of each performance's database row into an HTML
 * comment — 23 fields per event, including the two things the rendered programme never states:
 * the ticket price range and the free-entry flag. The leak is not intentional, so it is strictly
 * best-effort: [parseDelphiEventRecords] returns an empty map the day the venue notices, and
 * every event simply loses its price. Nothing load-bearing — date, title, identity, ticket link
 * — depends on it.
 *
 * @property pricePresale the lowest published price, or `null` when the venue states none.
 * @property priceNote the published range as rendered for display ("15–25 €"), or `null`.
 * @property free whether the venue flagged the performance as free entry.
 */
data class DelphiEventRecord(
    val pricePresale: BigDecimal?,
    val priceNote: String?,
    val free: Boolean
)

/**
 * Parses every leaked record into a map keyed by [performance key][delphiPerformanceKey], so a
 * record matches the rendered row for the same production and start time. The dump is one
 * block *before* the programme table, not beside each row, so the join is on
 * `(production id, start instant)` rather than document order.
 */
fun parseDelphiEventRecords(document: Document): Map<String, DelphiEventRecord> =
    document
        .select("*")
        .flatMap { element -> element.childNodes().filterIsInstance<Comment>() }
        .mapNotNull { comment -> parseRecord(comment.data) }
        .toMap()
        .also { logger.info { "Recovered ${it.size} leaked price record(s) from the Delphi programme page" } }

/**
 * The join key for one performance: production id and local start time. Both sides can state
 * these — the rendered row from its `?prod=` link and date heading plus clock, the leaked
 * record from `event_FK_production` and `event_Zeit`.
 */
fun delphiPerformanceKey(
    productionId: String,
    start: LocalDateTime
): String = "$productionId@$start"

/** Parses one `var_dump()` comment, or `null` when it is an ordinary HTML comment. */
private fun parseRecord(data: String): Pair<String, DelphiEventRecord>? {
    val fields =
        FIELD_PATTERN
            .findAll(data)
            .associate { it.groupValues[1] to it.groupValues[2] }
            .takeIf { it.containsKey(PRODUCTION_FIELD) }
            .orEmpty()

    val productionId = fields[PRODUCTION_FIELD]?.takeIf { it.isNotBlank() }
    val start = fields[TIMESTAMP_FIELD]?.toLongOrNull()?.let { LocalDateTime.ofInstant(Instant.ofEpochSecond(it), BERLIN) }

    // The production-level amounts carry cents where the per-event copies are rounded to whole
    // euros ("29.95" vs "29"), so they win wherever the venue filled them in.
    val from = fields.amount(PRODUCTION_PRICE_FROM) ?: fields.amount(EVENT_PRICE_FROM)
    val to = fields.amount(PRODUCTION_PRICE_TO) ?: fields.amount(EVENT_PRICE_TO)

    return if (productionId == null || start == null) {
        null
    } else {
        delphiPerformanceKey(productionId, start) to
            DelphiEventRecord(
                pricePresale = from,
                priceNote = priceNote(from, to),
                free = fields[FREE_ENTRY_FIELD] == "1"
            )
    }
}

/** Reads a euro amount field, treating the venue's "0" placeholder as "no price stated". */
private fun Map<String, String>.amount(field: String): BigDecimal? =
    get(field)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.toBigDecimalOrNull()
        ?.takeIf { it.signum() > 0 }

/** Renders the published range the way the venue would ("15–25 €", or "34,75 €" when flat). */
private fun priceNote(
    from: BigDecimal?,
    to: BigDecimal?
): String? =
    when {
        from == null -> null
        to == null || to == from -> "${from.germanAmount()} €"
        else -> "${from.germanAmount()}–${to.germanAmount()} €"
    }

/** Formats an amount the German way, dropping a whole-euro decimal part ("29.95" → "29,95", "15" → "15"). */
private fun BigDecimal.germanAmount(): String = stripTrailingZeros().toPlainString().replace('.', ',')

/** Matches one `["key"]=> string(n) "value"` pair of the dump. */
private val FIELD_PATTERN = Regex("""\["(\w+)"]=>\s*\w+\(\d+\)\s*"([^"]*)"""")

private const val PRODUCTION_FIELD = "event_FK_production"
private const val TIMESTAMP_FIELD = "event_Zeit"
private const val FREE_ENTRY_FIELD = "event_EintrittFrei"
private const val EVENT_PRICE_FROM = "event_BetragAb"
private const val EVENT_PRICE_TO = "event_BetragBis"
private const val PRODUCTION_PRICE_FROM = "production_BetragAb"
private const val PRODUCTION_PRICE_TO = "production_BetragBis"
