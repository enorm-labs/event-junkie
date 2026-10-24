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

    /** A number with its decimals or minutes: "18,50", "20.00", "19:30". Its digits up to the first separator are the number it names. */
    private val NUMBER = Regex("\\d+(?:[.,:]\\d+)*")

    private const val HALF_DAY = 12

    /** A quote shorter than this proves nothing, and a longer one is no quote but a copy. */
    val EVIDENCE_LENGTH = 3..500

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

    /**
     * [text] names the hour of [time], on the 24-hour clock or the 12-hour one ("9 pm"), as a number of its own.
     * The 9 in "19 Uhr" names no 9 pm.
     */
    fun namesHour(
        time: LocalTime,
        text: String
    ): Boolean {
        val twelve = (time.hour % HALF_DAY).takeIf { it != 0 } ?: HALF_DAY
        return numbersIn(text).any { it == time.hour || it == twelve }
    }

    /** [text] names the whole euros of [price] as a number of its own. The 5 in "15 €" and the 50 in "18,50 €" name no 5 € or 50 €. */
    fun namesPrice(
        price: BigDecimal,
        text: String
    ): Boolean = price.toInt() in numbersIn(text)

    private fun numbersIn(text: String): Sequence<Int> = NUMBER.findAll(text).mapNotNull { it.value.takeWhile(Char::isDigit).toIntOrNull() }
}
