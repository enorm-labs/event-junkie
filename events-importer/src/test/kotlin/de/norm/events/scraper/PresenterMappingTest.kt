package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PresenterMappingTest {
    @Test
    fun `splitPromoterCredit splits co-promoters and keeps a name that only contains a joiner`() {
        splitPromoterCredit("LOFT, Musikexpress, Rausgegangen und Bedroomdisco") shouldBe
            listOf("LOFT", "Musikexpress", "Rausgegangen", "Bedroomdisco")
        splitPromoterCredit("DRIFT & Puschen") shouldBe listOf("DRIFT", "Puschen")
        splitPromoterCredit("Selecta G & Seven Inch Soldiers") shouldBe listOf("Selecta G", "Seven Inch Soldiers")
        splitPromoterCredit("Loft & Trinity Music and Greyzone") shouldBe listOf("Loft", "Trinity Music", "Greyzone")
        splitPromoterCredit("aufnahme + wiedergabe") shouldBe listOf("aufnahme + wiedergabe")
        splitPromoterCredit("Now And Ever Music") shouldBe listOf("Now And Ever Music")
        splitPromoterCredit("B&C Productions") shouldBe listOf("B&C Productions")
        splitPromoterCredit(" , ") shouldBe emptyList()
    }

    @Test
    fun `promoterFromCredit strips the presenter verb after the name`() {
        promoterFromCredit("Konzertbüro Schoneberg presents") shouldBe "Konzertbüro Schoneberg"
        promoterFromCredit("FKP Scorpio präsentiert:") shouldBe "FKP Scorpio"
        promoterFromCredit("Trinity Music pres.") shouldBe "Trinity Music"
        promoterFromCredit("little league shows present:") shouldBe "little league shows"
        promoterFromCredit("M:Soundtrack prsts:") shouldBe "M:Soundtrack"
        promoterFromCredit("old & fast prsnts") shouldBe "old & fast"
    }

    @Test
    fun `promoterFromCredit strips a presenter intro before the name`() {
        promoterFromCredit("präsentiert von: Loft Concerts") shouldBe "Loft Concerts"
        promoterFromCredit("Presented by Goodlive") shouldBe "Goodlive"
        promoterFromCredit("Eine Veranstaltung von Berlin Beat") shouldBe "Berlin Beat"
    }

    @Test
    fun `promoterFromCredit keeps a company whose name ends in Presents`() {
        promoterFromCredit("AEG Presents") shouldBe "AEG Presents"
        promoterFromCredit("AEG Presents presents:") shouldBe "AEG Presents"
        promoterFromCredit("Pansy Presents") shouldBe "Pansy Presents"
    }

    @Test
    fun `promoterFromCredit keeps a name that only contains the word and drops an empty credit`() {
        promoterFromCredit("Presentation Records") shouldBe "Presentation Records"
        promoterFromCredit("präsentiert").shouldBeNull()
        promoterFromCredit(" ").shouldBeNull()
        promoterFromCredit(null).shouldBeNull()
    }
}
