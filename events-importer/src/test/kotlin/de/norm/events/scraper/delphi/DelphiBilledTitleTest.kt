package de.norm.events.scraper.delphi

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DelphiBilledTitleTest {
    @Test
    fun `knows a Candlelight programme, whose title names no act on stage`() {
        // Events 12694 and 12695.
        isCandlelightProgramme("Candlelight: Coldplay & Imagine Dragons | Rock-Hymnen") shouldBe true
        isCandlelightProgramme("Patrick Wolf - Yulenite") shouldBe false
    }

    @Test
    fun `bills the act the house series presents, and leaves other titles alone`() {
        // 2 October 2026; the page reads "Heute präsentiert Delphis Orakel / Stroum: 3rd live".
        delphiBilledTitle("Delphis Orakel – Stroum") shouldBe "Stroum"
        delphiBilledTitle("Schwanensee – Jenseits der Bühne") shouldBe "Schwanensee – Jenseits der Bühne"
        delphiBilledTitle("Delphis Orakel") shouldBe "Delphis Orakel"
        // Event 12693.
        delphiBilledTitle("Patrick Wolf - Yulenite") shouldBe "Patrick Wolf"
        // Event 11842: the band brings its All Stars formation.
        // The house misspells the band; its production page spells it Savoy Satellites.
        delphiBilledTitle("SAVOY SATTELITES – All Stars feat. Emil Wahlgren") shouldBe "SAVOY SATELLITES feat. Emil Wahlgren"
    }
}
