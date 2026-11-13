package de.norm.events.scraper.columbiatheater

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ColumbiaTheaterBlurbPricesTest {
    @Test
    fun `reads presale and box office from one line or from two`() {
        parseColumbiaTheaterBlurbPrices("VVK: 28 € zzgl. Gebühr / AK: 32 €") shouldBe
            ColumbiaTheaterBlurbPrices(BigDecimal("28"), BigDecimal("32"), "VVK: 28 € zzgl. Gebühr / AK: 32 €")
        parseColumbiaTheaterBlurbPrices("Vorverkauf: 30,00 €\nAbendkasse: 35,00 €") shouldBe
            ColumbiaTheaterBlurbPrices(BigDecimal("30.00"), BigDecimal("35.00"))
        parseColumbiaTheaterBlurbPrices("Tickets: 25 € · Abendkasse 30 €") shouldBe
            ColumbiaTheaterBlurbPrices(BigDecimal("25"), BigDecimal("30"), "Tickets: 25 € · Abendkasse 30 €")
    }

    @Test
    fun `stores the lowest amount of a range and keeps the range as the note`() {
        parseColumbiaTheaterBlurbPrices("Tickets: 35,00 € – 49,00 €") shouldBe
            ColumbiaTheaterBlurbPrices(presale = BigDecimal("35.00"), note = "Tickets: 35,00 € – 49,00 €")
        parseColumbiaTheaterBlurbPrices("Ticketpreis ab 29 Euro").note shouldBe "Ticketpreis ab 29 Euro"
    }

    @Test
    fun `reads no price from a number in the prose`() {
        val prose =
            """
            Ab dem 26. Januar 2026 heißt es: Komm vorbei.
            Einlass ab 18 Jahren, Jugendliche ab 16 in Begleitung.
            Der Vorverkauf beginnt am 31.07.2026 ab 11:00 Uhr
            Tickets erhältlich auf eventim.de
            Das Album erschien 2011 und kostete damals 15 €.
            Preisverleihung um 20 Uhr
            """.trimIndent()
        parseColumbiaTheaterBlurbPrices(prose) shouldBe ColumbiaTheaterBlurbPrices()
        parseColumbiaTheaterBlurbPrices(null) shouldBe ColumbiaTheaterBlurbPrices()
    }

    @Test
    fun `keeps the first price line of each kind`() {
        parseColumbiaTheaterBlurbPrices("Abendkasse: 40,00 €\nAbendkasse: 45,00 €") shouldBe
            ColumbiaTheaterBlurbPrices(boxOffice = BigDecimal("40.00"))
    }
}
