package de.norm.events.event

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Properties of [normalizeMoneyScale] for any amount: `MoneyExtensionsTest` pins the examples. `runBlocking`, not `runTest`,
 * because this module has no coroutines-test. It is typed `<Unit>`: a test method that returns `checkAll`'s result is not
 * void, and JUnit 5 silently skips it.
 */
class MoneyExtensionsPropertyTest {
    @Test
    fun `every amount comes out at scale 2, and normalizing twice changes nothing`() =
        runBlocking<Unit> {
            checkAll(AMOUNT) { amount ->
                val normalized = amount.normalizeMoneyScale()
                normalized.scale() shouldBe 2
                normalized.normalizeMoneyScale() shouldBe normalized
            }
        }

    @Test
    fun `the normalized amount is at most half a cent away`() =
        runBlocking<Unit> {
            checkAll(AMOUNT) { amount ->
                (amount.normalizeMoneyScale().subtract(amount).abs() <= HALF_A_CENT) shouldBe true
            }
        }

    // The reason the scale exists: `10` from a scraper and `10.00` from the database must compare equal (contentEquals).
    @Test
    fun `trailing zeros do not change the normalized amount`() =
        runBlocking<Unit> {
            checkAll(AMOUNT, Arb.int(0..4)) { amount, zeros ->
                amount.setScale(amount.scale() + zeros).normalizeMoneyScale() shouldBe amount.normalizeMoneyScale()
                amount.stripTrailingZeros().normalizeMoneyScale() shouldBe amount.normalizeMoneyScale()
            }
        }

    @Test
    fun `an amount in whole cents keeps its value`() =
        runBlocking<Unit> {
            checkAll(Arb.long(-10_000_000L..10_000_000L)) { cents ->
                val amount = BigDecimal.valueOf(cents, 2)
                amount.normalizeMoneyScale().compareTo(amount) shouldBe 0
            }
        }

    @Test
    fun `normalizing keeps the order of two amounts and the sign of one`() =
        runBlocking<Unit> {
            checkAll(AMOUNT, AMOUNT) { first, second ->
                if (first <= second) (first.normalizeMoneyScale() <= second.normalizeMoneyScale()) shouldBe true
                first.negate().normalizeMoneyScale() shouldBe first.normalizeMoneyScale().negate()
            }
        }

    private companion object {
        val HALF_A_CENT = BigDecimal("0.005")

        /** Any amount a scraper or the admin API can hand in: up to ±10 million at scales from -3 to 8. */
        val AMOUNT: Arb<BigDecimal> =
            Arb.bind(
                Arb.long(-1_000_000_000L..1_000_000_000L),
                Arb.int(-3..8)
            ) { unscaled, scale -> BigDecimal.valueOf(unscaled, scale) }
    }
}
