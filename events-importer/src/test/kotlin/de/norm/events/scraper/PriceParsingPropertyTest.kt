package de.norm.events.scraper

import de.norm.events.common.NameArbs
import de.norm.events.event.normalizeMoneyScale
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Round trips through the shared price readers: `PriceParsingExtensionsTest` pins the examples. */
class PriceParsingPropertyTest {
    @Test
    fun `an amount reads back in every spelling a venue prints`() =
        runTest {
            checkAll(CENTS) { cents ->
                spellings(cents).forEach { text -> parsePriceValue(text).money() shouldBe money(cents) }
            }
        }

    @Test
    fun `an amount reads back inside prose`() =
        runTest {
            checkAll(CENTS, Arb.element(SPELLERS), PROSE, PROSE) { cents, spell, before, after ->
                parsePriceValue("$before ${spell(cents)} $after").money() shouldBe money(cents)
            }
        }

    @Test
    fun `a clock or date before the amount is not read as one`() =
        runTest {
            checkAll(CENTS, Arb.element(SPELLERS), Arb.int(0..23), Arb.int(1..28)) { cents, spell, hour, day ->
                val clock = "${hour.toString().padStart(2, '0')}:00"
                parsePriceValue("Einlass $clock, Beginn $clock Uhr, ${spell(cents)}").money() shouldBe money(cents)
                parsePriceValue("Am $day.10.2026 um $clock: ${spell(cents)}").money() shouldBe money(cents)
            }
        }

    @Test
    fun `a booking fee after the price is not the price`() =
        runTest {
            checkAll(CENTS, Arb.int(1..500), Arb.element(PRESALE_LABELS), Arb.element("zzgl.", "zzgl", "plus", "+")) { cents, fee, label, plus ->
                parseLabelledPrices("$label: ${euros(cents)} € $plus ${euros(fee)} € Gebühr").normalized().presale shouldBe money(cents)
            }
        }

    @Test
    fun `several amounts read back in reading order`() =
        runTest {
            checkAll(Arb.list(CENTS, 1..5), Arb.element(SPELLERS), Arb.element(" / ", ", ", " · ", " oder ", " - ")) { amounts, spell, separator ->
                euroAmounts(amounts.joinToString(separator) { spell(it) }).map { it.normalizeMoneyScale() } shouldBe amounts.map(::money)
            }
        }

    @Test
    fun `text without a euro sign or word holds no amount`() =
        runTest {
            checkAll(NO_CURRENCY) { text -> euroAmounts(text).shouldBeEmpty() }
        }

    @Test
    fun `an amount is never negative, and no text makes the reader throw`() =
        runTest {
            checkAll(NameArbs.anyName) { text ->
                euroAmounts(text).forEach { (it.signum() >= 0) shouldBe true }
                parseLabelledPrices(text)
            }
        }

    @Test
    fun `each label takes the amount after it, whichever comes first`() =
        runTest {
            checkAll(
                CENTS,
                CENTS,
                Arb.element(PRESALE_LABELS),
                Arb.element(BOX_OFFICE_LABELS),
                Arb.element(SPELLERS)
            ) { presale, door, presaleLabel, doorLabel, spell ->
                val expected = LabelledPrices(money(presale), money(door))
                parseLabelledPrices("$presaleLabel: ${spell(presale)} / $doorLabel: ${spell(door)}").normalized() shouldBe expected
                parseLabelledPrices("$doorLabel ${spell(door)} · $presaleLabel ${spell(presale)}").normalized() shouldBe expected
            }
        }

    @Test
    fun `a tiered price stores its lowest tier, and a concession after it is not the price`() =
        runTest {
            checkAll(
                Arb.list(CENTS, 1..4),
                CENTS,
                Arb.element(PRESALE_LABELS),
                Arb.element("ermäßigt", "Ermässigt", "erm.", "reduced", "Studierende")
            ) { tiers, concession, label, concessionLabel ->
                val text = "$label ${tiers.joinToString(" / ") { "${euros(it)} €" }} zzgl. Gebühr, $concessionLabel ${euros(concession)} €"
                parseLabelledPrices(text).normalized().presale shouldBe money(tiers.min())
            }
        }

    @Test
    fun `an ab before the amount marks a from price, and its absence does not`() =
        runTest {
            checkAll(CENTS, Arb.element(PRESALE_LABELS + BOX_OFFICE_LABELS), Arb.element("ab ", "Ab ", "ab: ", "AB ")) { cents, label, from ->
                parseLabelledPrices("$label $from${euros(cents)} €").fromPrice shouldBe true
                parseLabelledPrices("$label ${euros(cents)} €").fromPrice shouldBe false
            }
        }

    @Test
    fun `a box-office label is one in any letter case, and a presale label never is`() =
        runTest {
            checkAll(Arb.element(BOX_OFFICE_LABELS), Arb.element(PRESALE_LABELS), Arb.element(LETTER_CASES)) { door, presale, case ->
                isBoxOfficeLabel(case(door)) shouldBe true
                isBoxOfficeLabel(case(presale)) shouldBe false
            }
        }

    @Test
    fun `normalizing to the money scale keeps the value of an amount in cents`() =
        runTest {
            checkAll(CENTS) { cents ->
                val normalized = BigDecimal.valueOf(cents.toLong(), 2).stripTrailingZeros().normalizeMoneyScale()
                normalized.scale() shouldBe 2
                normalized.compareTo(BigDecimal.valueOf(cents.toLong(), 2)) shouldBe 0
                normalized.normalizeMoneyScale() shouldBe normalized
            }
        }

    private fun BigDecimal?.money(): BigDecimal? = this?.normalizeMoneyScale()

    private fun LabelledPrices.normalized() = copy(presale = presale.money(), boxOffice = boxOffice.money())

    private companion object {
        /** Up to 999,99 €: German pages print no thousands separator for a ticket. */
        val CENTS: Arb<Int> = Arb.int(0..99_999)

        fun money(cents: Int): BigDecimal = BigDecimal.valueOf(cents.toLong(), 2)

        fun euros(cents: Int): String = "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"

        private fun dotted(cents: Int): String = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

        /** Every spelling an amount takes; the whole-euro ones only for a whole amount. */
        val SPELLERS: List<(Int) -> String> =
            listOf(
                { "${euros(it)} €" },
                { "${euros(it)}€" },
                { "${dotted(it)}€" },
                { "${euros(it)} €" },
                { "€${euros(it)}" },
                { "€ ${dotted(it)}" },
                { "${euros(it)} EUR" },
                { "${euros(it)} Euro" }
            )

        fun spellings(cents: Int): List<String> =
            SPELLERS.map { it(cents) } +
                if (cents % 100 == 0) listOf("${cents / 100} €", "${cents / 100},- €", "€${cents / 100}", "${cents / 100} Euro") else emptyList()

        /** Text around a price: letters, spaces and punctuation, but no digit, no `€` and no `eur`. */
        val PROSE: Arb<String> =
            Arb
                .list(Arb.element(("abcdefghijklmnopqrstuvwxyzäöüß ").toList() + listOf('(', ')', ':', '!', '·', '*')), 0..20)
                .map { it.joinToString("") }
                .filter { "eur" !in it.lowercase() && !it.endsWith("ab") }

        /** Any text with digits, clocks and dates, but no `€` and no `eur`. */
        val NO_CURRENCY: Arb<String> =
            Arb
                .list(Arb.element(("abcdefghijklmnopqrstuvwxyz0123456789 .,:-/ ").toList()), 0..30)
                .map { it.joinToString("") }
                .filter { "eur" !in it.lowercase() }

        val PRESALE_LABELS = listOf("VVK", "vvk", "Vorverkauf", "Presale", "Pre-sale", "Advance")
        val BOX_OFFICE_LABELS = listOf("AK", "Abendkasse", "Tageskasse", "Box Office", "boxoffice", "at the door", "Door price")
        val LETTER_CASES: List<(String) -> String> = listOf({ it.lowercase() }, { it.uppercase() }, { it })
    }
}
