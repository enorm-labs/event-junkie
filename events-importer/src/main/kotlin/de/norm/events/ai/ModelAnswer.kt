package de.norm.events.ai

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalTime

/**
 * Reads one value out of the JSON a model answered, for every validator that checks one (ADR-050).
 *
 * Each function returns null for a value the model left out or wrote unusably. The caller drops that
 * field and keeps the rest of the answer, so one bad value never costs the whole response.
 */
object ModelAnswer {
    /** A price above this is a typo or a festival pass, and either way not a ticket price to propose. */
    private val MAX_PRICE = BigDecimal("500")

    private val CODE_FENCE = Regex("^```(?:json)?\\s*|\\s*```$")
    private val TIME = Regex("^([01]?\\d|2[0-3])[:.]([0-5]\\d)$")

    private val mapper: JsonMapper = JsonMapper.builder().build()

    /**
     * The object the model answered, or null when the text parses as something other than an object.
     *
     * A model fences its JSON often enough that stripping the fence belongs here, not at a call site.
     *
     * @throws tools.jackson.core.JacksonException for text that is no JSON at all. The caller logs it,
     *   because the name of the answer is the part worth reading.
     */
    fun jsonObject(raw: String): JsonNode? = mapper.readTree(raw.trim().replace(CODE_FENCE, "")).takeIf { it.isObject }

    /** A price the model wrote as a number or as text, at the scale every stored price carries. */
    fun price(node: JsonNode?): BigDecimal? {
        if (node == null || node.isNull) return null
        val value =
            if (node.isNumber) {
                node.decimalValue()
            } else {
                node
                    .asString("")
                    .trim()
                    .replace(',', '.')
                    .toBigDecimalOrNull()
            }
        return value?.takeIf { it > BigDecimal.ZERO && it <= MAX_PRICE }?.setScale(2, RoundingMode.HALF_UP)
    }

    /** A time of day on the 24-hour clock, written with either separator: `20:00` or `20.00`. */
    fun time(value: String): LocalTime? =
        TIME.matchEntire(value.trim())?.let { match -> LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()) }
}
