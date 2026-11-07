package de.norm.events.scraper

import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/** The `offers.availability` terms that mean no ticket is left. */
private val SOLD_OUT_AVAILABILITY = listOf("SoldOut", "OutOfStock")

/** The event's `offers` as a list, whether the venue publishes one offer object or an array. */
fun JsonNode.schemaOffers(): List<JsonNode> {
    val offers = path("offers")
    return if (offers.isArray) offers.filter { it.isObject } else listOfNotNull(offers.takeIf { it.isObject })
}

/** Each offer's `price` that reads as a number, in offer order: a venue takes the first or the lowest. */
fun JsonNode.schemaOfferPrices(): List<BigDecimal> = schemaOffers().mapNotNull { it.stringOrNull("price")?.toBigDecimalOrNull() }

/**
 * Whether every offer's `availability` is `SoldOut` or `OutOfStock`. An event with no offers sells
 * nothing online, so it is never sold out.
 */
fun JsonNode.schemaSoldOut(): Boolean {
    val offers = schemaOffers()
    return offers.isNotEmpty() &&
        offers.all { offer ->
            val availability = offer.stringOrNull("availability").orEmpty()
            SOLD_OUT_AVAILABILITY.any { availability.endsWith(it, ignoreCase = true) }
        }
}
