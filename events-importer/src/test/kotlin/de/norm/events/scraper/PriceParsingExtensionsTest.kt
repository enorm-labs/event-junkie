package de.norm.events.scraper

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import org.junit.jupiter.api.Test
import java.math.BigDecimal

// Jsoup-parsed snippets rather than fixtures: the `.price` parser reads two selectors and nothing else.
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
        parsePresaleAndBoxOfficePrices(prices(block("39,90€", "VVK"))).presale shouldBe BigDecimal("39.90")
        parsePresaleAndBoxOfficePrices(prices(block("35.20€", "VVK"))).presale shouldBe BigDecimal("35.20")
        parsePresaleAndBoxOfficePrices(prices(block("30,00&nbsp;€", "VVK"))).presale shouldBe BigDecimal("30.00")
    }

    @Test
    fun `parsePresaleAndBoxOfficePrices yields nulls when a category has no parseable value`() {
        val (presale, boxOffice) = parsePresaleAndBoxOfficePrices(prices(block("kostenlos", "Vorverkauf")))
        presale.shouldBeNull()
        boxOffice.shouldBeNull()
        val empty = parsePresaleAndBoxOfficePrices(prices())
        empty shouldBe PlatformPrices()
    }

    @Test
    fun `parsePresaleAndBoxOfficePrices skips a concession for both columns and keeps every tier as the note`() {
        val prices = parsePresaleAndBoxOfficePrices(prices(block("27.00€", "U25"), block("32.00€", "Vorverkauf (+ Geb.)")))
        prices shouldBe PlatformPrices(BigDecimal("32.00"), null, "U25: 27.00€ · Vorverkauf (+ Geb.): 32.00€")
    }

    @Test
    fun `parsePresaleAndBoxOfficePrices leaves a tier with no amount out of the note`() {
        // Brakence at Astra, 28 May 2027 (#5551): a presale start date in a `.price` block of its own.
        val prices =
            parsePresaleAndBoxOfficePrices(
                prices(
                    block("30.00€", "Vorverkauf U25 (+ Geb.)"),
                    block("36.00€", "Vorverkauf (+ Geb.)"),
                    block("VVK Start Fr. 30.10. - 10:00", ""),
                    block("", "VVK Start Fr. 30.10. - 10:00")
                )
            )
        prices shouldBe PlatformPrices(BigDecimal("36.00"), null, "Vorverkauf U25 (+ Geb.): 30.00€ · Vorverkauf (+ Geb.): 36.00€")
    }

    @Test
    fun `parsePresaleAndBoxOfficePrices reads every concession label the venues print`() {
        listOf("U25", "u 18", "U27 Ticket", "ermäßigt", "Ermässigt", "erm.", "Schüler", "Schueler", "Student", "Studenten", "Studierende", "Mitglieder")
            .forEach { label ->
                val prices = parsePresaleAndBoxOfficePrices(prices(block("10,00€", label), block("20,00€", "Abendkasse"), block("15,00€", "VVK")))
                prices.presale shouldBe BigDecimal("15.00")
                prices.boxOffice shouldBe BigDecimal("20.00")
                prices.note shouldBe "$label: 10,00€ · Abendkasse: 20,00€ · VVK: 15,00€"
            }
    }

    @Test
    fun `parsePresaleAndBoxOfficePrices keeps a concession box-office tier out of the box-office column`() {
        val prices = parsePresaleAndBoxOfficePrices(prices(block("12,00€", "Abendkasse ermäßigt"), block("18,00€", "Abendkasse")))
        prices.boxOffice shouldBe BigDecimal("18.00")
        prices.presale.shouldBeNull()
    }

    @Test
    fun `parsePresaleAndBoxOfficePrices stores only the note for a page with only a concession price`() {
        val prices = parsePresaleAndBoxOfficePrices(prices(block("27.00€", "U25"), block("27.00€", "U25")))
        prices shouldBe PlatformPrices(note = "U25: 27.00€")
    }

    @Test
    fun `parsePresaleAndBoxOfficePrices writes no note without a concession, and reads no age in a longer word or number`() {
        parsePresaleAndBoxOfficePrices(prices(block("25,00€", "Vorverkauf"), block("30,00€", "Abendkasse"))).note.shouldBeNull()
        parsePresaleAndBoxOfficePrices(prices(block("25,00€", "Tour U2025"))).presale shouldBe BigDecimal("25.00")
        parsePresaleAndBoxOfficePrices(prices(block("25,00€", "Club25 VVK"))).presale shouldBe BigDecimal("25.00")
    }

    @Test
    fun `parsePriceValue reads every spelling of a euro amount`() {
        parsePriceValue("39,90€") shouldBe BigDecimal("39.90")
        parsePriceValue("30,00\u00a0€") shouldBe BigDecimal("30.00")
        parsePriceValue("€ 7,50") shouldBe BigDecimal("7.50")
        parsePriceValue("13,00 EUR") shouldBe BigDecimal("13.00")
        parsePriceValue("32 Euro") shouldBe BigDecimal("32")
        parsePriceValue("15,- €") shouldBe BigDecimal("15")
        parsePriceValue("AK:35€") shouldBe BigDecimal("35")
        parsePriceValue("tba").shouldBeNull()
        parsePriceValue(null).shouldBeNull()
    }

    @Test
    fun `euroAmounts keeps reading order and refuses an amount run into a clock time`() {
        euroAmounts("free before 19:00, €5, 10€, €5") shouldBe listOf(BigDecimal("5"), BigDecimal("10"), BigDecimal("5"))
        euroAmounts("€520:00 free").shouldBeEmpty()
        euroAmounts("Eurovision").shouldBeEmpty()
    }

    @Test
    fun `parseLabelledPrices assigns each amount to the label before it`() {
        parseLabelledPrices("VVK: 28 € (zzgl. Gebühr) / AK: 32 €") shouldBe LabelledPrices(BigDecimal("28"), BigDecimal("32"))
        parseLabelledPrices("Abendkasse 30 € · Vorverkauf 25 €") shouldBe LabelledPrices(BigDecimal("25"), BigDecimal("30"))
        parseLabelledPrices("Tickets: 25,00€ Tageskasse: 30 Euro").boxOffice shouldBe BigDecimal("30")
        parseLabelledPrices("20 € at the door").boxOffice.shouldBeNull()
        parseLabelledPrices("Presale 12 € · at the door 15 €") shouldBe LabelledPrices(BigDecimal("12"), BigDecimal("15"))
    }

    @Test
    fun `parseLabelledPrices stores the lowest tier and marks a from price`() {
        parseLabelledPrices("Vorverkauf 18 €/ 12 €/ 25 € zzgl. Gebühren * Abendkasse 30 €") shouldBe
            LabelledPrices(BigDecimal("12"), BigDecimal("30"))
        parseLabelledPrices("VVK: ab 74,99 €") shouldBe LabelledPrices(presale = BigDecimal("74.99"), fromPrice = true)
        parseLabelledPrices("Abendkasse: Ab 20 Euro").fromPrice shouldBe true
        parseLabelledPrices("VVK ab: 69,99 € zzgl. Gebühr").fromPrice shouldBe true
    }

    // Found by PriceParsingPropertyTest: the fee was the lowest amount after the label, so it was stored as the price.
    @Test
    fun `parseLabelledPrices reads past a booking fee added to the price`() {
        parseLabelledPrices("VVK: 28 € zzgl. 2,50 € Gebühr / AK: 32 €") shouldBe LabelledPrices(BigDecimal("28"), BigDecimal("32"))
        parseLabelledPrices("Vorverkauf 22,90 € +0,45 € Ticket-Servicegebühr").presale shouldBe BigDecimal("22.90")
        parseLabelledPrices("Presale 15 € plus 1,50 € fee").presale shouldBe BigDecimal("15")
        parseLabelledPrices("VVK 12 € + 15 €").presale shouldBe BigDecimal("12")
    }

    @Test
    fun `parseLabelledPrices skips concessions, empty slots and unlabelled amounts`() {
        parseLabelledPrices("Abendkasse 15 € / ermäßigt 10 €").boxOffice shouldBe BigDecimal("15")
        parseLabelledPrices("Abendkasse: TBA · VVK 12 €") shouldBe LabelledPrices(presale = BigDecimal("12"))
        parseLabelledPrices("Einlass: 19:00 Beginn: 20:00 Abendkasse:") shouldBe LabelledPrices()
        parseLabelledPrices(null) shouldBe LabelledPrices()
    }

    @Test
    fun `germanDecimals reads every bare number, comma or point, without a currency`() {
        germanDecimals("18,00 / 15,00") shouldBe listOf(BigDecimal("18.00"), BigDecimal("15.00"))
        germanDecimals("VVK 9.5, AK 12") shouldBe listOf(BigDecimal("9.5"), BigDecimal("12"))
        germanDecimals("tba").shouldBeEmpty()
    }

    @Test
    fun `germanDecimalOrNull reads a decimal comma and refuses what is not a number`() {
        "15,90".germanDecimalOrNull() shouldBe BigDecimal("15.90")
        "15.90".germanDecimalOrNull() shouldBe BigDecimal("15.90")
        "frei".germanDecimalOrNull().shouldBeNull()
        "".germanDecimalOrNull().shouldBeNull()
    }

    @Test
    fun `price labels match whole words only`() {
        isBoxOfficeLabel("Abendkasse") shouldBe true
        isBoxOfficeLabel("AK / Tageskasse") shouldBe true
        isBoxOfficeLabel("VVK") shouldBe false
        isBoxOfficeLabel("Doors") shouldBe false
    }
}
