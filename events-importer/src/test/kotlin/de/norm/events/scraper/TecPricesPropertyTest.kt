package de.norm.events.scraper

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

/** Properties of the Events Calendar price readers: `TecPricesTest` pins the examples. */
class TecPricesPropertyTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `the from price is the lowest tier, or null when there is none or it is free`() =
        runTest {
            checkAll(Arb.list(TIER, 0..6)) { tiers ->
                tecFromPrice(tiers) shouldBe tiers.minOrNull()?.takeIf { it.signum() > 0 }
            }
        }

    @Test
    fun `the order of the tiers does not change the from price`() =
        runTest {
            checkAll(Arb.list(TIER, 0..6)) { tiers ->
                tecFromPrice(tiers.shuffled()) shouldBe tecFromPrice(tiers)
                tecFromPrice(tiers.reversed()) shouldBe tecFromPrice(tiers)
            }
        }

    @Test
    fun `cost_details reads every amount in order and skips every word`() =
        runTest {
            checkAll(Arb.list(VALUE, 0..6)) { values ->
                val json = values.joinToString(prefix = """{ "cost_details": { "values": [""", postfix = "] } }") { "\"${it.text}\"" }
                mapper.readTree(json).tecCostAmounts() shouldBe values.mapNotNull { it.amount }
            }
        }

    @Test
    fun `an event without cost_details has no amount`() =
        runTest {
            checkAll(Arb.element("{}", """{ "cost_details": {} }""", """{ "cost_details": { "values": [] } }""", """{ "cost": "10 €" }""")) { json ->
                mapper.readTree(json).tecCostAmounts().shouldBeEmpty()
            }
        }

    private data class Value(
        val text: String,
        val amount: BigDecimal?
    )

    private companion object {
        /** A tier in cents, free ones included: TEC writes `0` for a free entry. */
        val TIER: Arb<BigDecimal> = Arb.long(0L..20_000L).map { BigDecimal.valueOf(it, 2) }

        /** A `values` entry: an amount as TEC writes it (`10`, `15.5`, `15.50`), or a word it lets an organiser type. */
        val VALUE: Arb<Value> =
            Arb.choice(
                Arb.int(0..20_000).map { cents ->
                    val amount = BigDecimal.valueOf(cents.toLong(), 2)
                    val text = if (cents % 100 == 0) "${cents / 100}" else amount.toPlainString()
                    Value(text, BigDecimal(text))
                },
                Arb.element("spende", "tba", "frei", "Eintritt frei", "", "ab 10", "10 €", "kostenlos").map { Value(it, null) }
            )
    }
}
