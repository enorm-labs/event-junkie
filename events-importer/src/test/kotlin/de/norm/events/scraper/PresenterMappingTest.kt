package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PresenterMappingTest {
    @Test
    fun `promoterFromCredit strips the presenter verb after the name`() {
        promoterFromCredit("Konzertbüro Schoneberg presents") shouldBe "Konzertbüro Schoneberg"
        promoterFromCredit("FKP Scorpio präsentiert:") shouldBe "FKP Scorpio"
        promoterFromCredit("Trinity Music pres.") shouldBe "Trinity Music"
        promoterFromCredit("little league shows present:") shouldBe "little league shows"
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
