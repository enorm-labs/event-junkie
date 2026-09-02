package de.norm.events.event

import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import kotlin.test.Test

class MoneyExtensionsTest {
    @Test
    fun `normalizeMoneyScale sets scale to 2 for whole numbers`() {
        BigDecimal("10").normalizeMoneyScale() shouldBe BigDecimal("10.00")
    }

    @Test
    fun `normalizeMoneyScale preserves scale 2 values unchanged`() {
        BigDecimal("10.00").normalizeMoneyScale() shouldBe BigDecimal("10.00")
    }

    @Test
    fun `normalizeMoneyScale truncates scale 1 to scale 2`() {
        BigDecimal("10.0").normalizeMoneyScale() shouldBe BigDecimal("10.00")
    }

    @Test
    fun `normalizeMoneyScale rounds scale 3 to scale 2 using HALF_UP`() {
        BigDecimal("10.125").normalizeMoneyScale() shouldBe BigDecimal("10.13")
        BigDecimal("10.124").normalizeMoneyScale() shouldBe BigDecimal("10.12")
    }

    @Test
    fun `normalizeMoneyScale handles zero`() {
        BigDecimal("0").normalizeMoneyScale() shouldBe BigDecimal("0.00")
        BigDecimal("0.0").normalizeMoneyScale() shouldBe BigDecimal("0.00")
    }

    @Test
    fun `normalized values are equal via BigDecimal equals`() {
        val fromScraper = BigDecimal("10").normalizeMoneyScale()
        val fromDatabase = BigDecimal("10.00").normalizeMoneyScale()
        fromDatabase shouldBe fromScraper
    }
}
