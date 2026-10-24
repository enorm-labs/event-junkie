package de.norm.events.scraper

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

class TecPricesTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `reads the amounts of cost_details and skips its words`() {
        mapper.readTree("""{ "cost_details": { "values": ["10", "spende", "15.50"] } }""").tecCostAmounts() shouldContainExactly
            listOf(BigDecimal("10"), BigDecimal("15.50"))
    }

    @Test
    fun `takes the lowest positive tier as the from price`() {
        tecFromPrice(listOf(BigDecimal("15"))) shouldBe BigDecimal("15")
        tecFromPrice(listOf(BigDecimal("12"), BigDecimal("8"), BigDecimal("20"))) shouldBe BigDecimal("8")
        tecFromPrice(listOf(BigDecimal("0"))).shouldBeNull()
        tecFromPrice(listOf(BigDecimal("0"), BigDecimal("10"))).shouldBeNull()
        tecFromPrice(emptyList()).shouldBeNull()
    }
}
