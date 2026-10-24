package de.norm.events.scraper

import tools.jackson.databind.JsonNode
import java.math.BigDecimal

// The price readers for a venue on The Events Calendar; the page and field readers are in TheEventsCalendar.kt.

/** The amounts in `cost_details.values`: one, two for a range, more for tiers. A word such as `spende` or `tba` is not one. */
fun JsonNode.tecCostAmounts(): List<BigDecimal> = path("cost_details").path("values").mapNotNull { it.asString("").toBigDecimalOrNull() }

/**
 * The presale price for [amounts]: the lowest tier, so a range reads as a "from" price and the note keeps the breakdown (#2083).
 * Null when the lowest tier is `0` or no amount is set; a free entry is a flag, not a price.
 */
fun tecFromPrice(amounts: List<BigDecimal>): BigDecimal? = amounts.minOrNull()?.takeIf { it.signum() > 0 }
