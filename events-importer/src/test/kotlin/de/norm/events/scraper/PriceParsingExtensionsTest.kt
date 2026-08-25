package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import org.junit.jupiter.api.Test
import java.math.BigDecimal

// Jsoup-parsed snippets rather than fixtures: the function reads two selectors and nothing else.
class PriceParsingExtensionsTest {
    private fun prices(vararg blocks: String): Elements = Jsoup.parseBodyFragment(blocks.joinToString("")).select(".price")

    private fun block(
        value: String,
        label: String
    ): String = """<div class="price"><span class="price__value">$value</span><span class="price__label">$label</span></div>"""

    @Test
    fun `parsePresaleAndBoxOfficePrices splits on the label, not the order`() {
        val (presale, boxOffice) = parsePresaleAndBoxOfficePrices(prices(block("39,90€", "Abendkasse"), block("35,20€", "Vorverkauf")))
        presale shouldBe BigDecimal("35.20")
        boxOffice shouldBe BigDecimal("39.90")
    }

    // Matched as a standalone token, so a label merely containing those letters is not swept up.
    @Test
    fun `parsePresaleAndBoxOfficePrices treats a bare AK label as box office`() {
        val (presale, boxOffice) = parsePresaleAndBoxOfficePrices(prices(block("18,00€", "AK"), block("15,00€", "VVK")))
        presale shouldBe BigDecimal("15.00")
        boxOffice shouldBe BigDecimal("18.00")
    }

    // The markup renders each price twice, for the mobile and desktop layouts.
    @Test
    fun `parsePresaleAndBoxOfficePrices keeps the first value seen for each category`() {
        val (presale, boxOffice) =
            parsePresaleAndBoxOfficePrices(
                prices(block("25,00€", "Vorverkauf"), block("99,00€", "Vorverkauf"), block("30,00€", "Abendkasse"), block("99,00€", "Abendkasse"))
            )
        presale shouldBe BigDecimal("25.00")
        boxOffice shouldBe BigDecimal("30.00")
    }

    @Test
    fun `parsePresaleAndBoxOfficePrices reads every value rendering the platform emits`() {
        parsePresaleAndBoxOfficePrices(prices(block("39,90€", "VVK"))).first shouldBe BigDecimal("39.90")
        parsePresaleAndBoxOfficePrices(prices(block("35.20€", "VVK"))).first shouldBe BigDecimal("35.20")
        parsePresaleAndBoxOfficePrices(prices(block("30,00&nbsp;€", "VVK"))).first shouldBe BigDecimal("30.00")
    }

    @Test
    fun `parsePresaleAndBoxOfficePrices yields nulls when a category has no parseable value`() {
        val (presale, boxOffice) = parsePresaleAndBoxOfficePrices(prices(block("kostenlos", "Vorverkauf")))
        presale.shouldBeNull()
        boxOffice.shouldBeNull()
        val empty = parsePresaleAndBoxOfficePrices(prices())
        empty.first.shouldBeNull()
        empty.second.shouldBeNull()
    }
}
