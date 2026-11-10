package de.norm.events.ai

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalTime

/**
 * Why an answer is no JSON, told without its words (#4108). The answer quotes a venue's text, which can carry a
 * person's contact, so the log gets its length, where the parser stopped, and the [shape] around that point.
 */
data class Unparseable(
    val length: Int,
    /** The character offset where the parser stopped, or null when Jackson reports none. */
    val offset: Long?,
    /** Up to [ModelAnswer.SHAPE_RADIUS] characters on each side of [offset]: each letter is `a`, each digit `9`, and `⟨here⟩` marks the offset. */
    val shape: String
) {
    override fun toString(): String = "$length character(s), stopped at ${offset ?: "an unknown offset"}: $shape"
}

/** The model answered text that does not parse as JSON. */
class UnparseableAnswer(
    val unparseable: Unparseable,
    cause: JacksonException
) : RuntimeException("The answer is not JSON: $unparseable", cause)

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

    /** What follows a `"` that ends a JSON string. */
    private const val STRING_END_FOLLOWERS = ":}]"
    private const val VALUE_STARTERS = "\"{[-0123456789"
    private val JSON_LITERAL = Regex("""(?:true|false|null)\b""")

    /** Enough of the shape on each side to show an unescaped quote and the key it sits in. */
    const val SHAPE_RADIUS = 30

    /** A quote shorter than this proves nothing, and a longer one is no quote but a copy. */
    val EVIDENCE_LENGTH = 3..500

    private val mapper: JsonMapper = JsonMapper.builder().build()

    /**
     * The object the model answered, or null when the text parses as something other than an object.
     *
     * A model fences its JSON often enough that stripping the fence belongs here, not at a call site.
     *
     * @throws UnparseableAnswer for text that is no JSON at all. The caller logs it and counts it, because the
     *   name of the answer is the part worth reading.
     */
    fun jsonObject(raw: String): JsonNode? {
        val text = closeLowNineQuotes(raw.trim().replace(CODE_FENCE, ""))
        return try {
            mapper.readTree(text).takeIf { it.isObject }
        } catch (e: JacksonException) {
            throw UnparseableAnswer(unparseable(text, e), e)
        }
    }

    /**
     * [json] with each `„` that a straight `"` closes inside a string value closed with `“` instead (#4758).
     *
     * The model quotes German text as `„…"`, as it does in a translation (`QuotePairs`), and the unescaped `"` ends the
     * string early. A `"` that a `:`, `}`, `]` or the end of the text follows ends the string and stays, so valid JSON
     * never changes. So does one that a `,` and the start of a JSON value follow. A `„` with another quote mark before its
     * `"` stays too.
     */
    internal fun closeLowNineQuotes(json: String): String {
        if ('„' !in json) return json
        val out = StringBuilder(json)
        var inString = false
        var i = 0
        while (i < out.length) {
            when (out[i]) {
                '\\' -> if (inString) i++
                '"' -> inString = !inString
                '„' -> if (inString) quoteCloser(out, i)?.let { out.setCharAt(it, '“') }
            }
            i++
        }
        return out.toString()
    }

    /** The index of the straight `"` that closes the `„` at [opener] and not its string, or null. */
    private fun quoteCloser(
        text: CharSequence,
        opener: Int
    ): Int? {
        var j = opener + 1
        while (j < text.length) {
            when (text[j]) {
                '„', '“', '”' -> return null
                '\\' -> j++
                '"' -> return j.takeUnless { endsString(text, it) }
            }
            j++
        }
        return null
    }

    private fun endsString(
        text: CharSequence,
        quote: Int
    ): Boolean {
        val rest = text.drop(quote + 1).trimStart()
        return when (rest.firstOrNull()) {
            null -> true

            // After a `,` valid JSON goes on with a value; plain text means the `"` closed a German quote (#4787).
            ',' -> rest.drop(1).trimStart().let { it.isEmpty() || it.first() in VALUE_STARTERS || JSON_LITERAL.matchesAt(it, 0) }

            else -> rest.first() in STRING_END_FOLLOWERS
        }
    }

    private fun unparseable(
        text: String,
        error: JacksonException
    ): Unparseable {
        val offset = error.location?.charOffset?.takeIf { it >= 0 }
        val shape =
            offset
                ?.let {
                    val at = it.toInt().coerceAtMost(text.length)
                    shapeOf(text.substring((at - SHAPE_RADIUS).coerceAtLeast(0), at)) + "⟨here⟩" +
                        shapeOf(text.substring(at, (at + SHAPE_RADIUS).coerceAtMost(text.length)))
                }.orEmpty()
        return Unparseable(text.length, offset, shape)
    }

    /** [text] with its words blanked out and its punctuation kept. */
    private fun shapeOf(text: String): String =
        text
            .map {
                when {
                    it.isLetter() -> 'a'
                    it.isDigit() -> '9'
                    it.isWhitespace() -> ' '
                    else -> it
                }
            }.joinToString("")

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
