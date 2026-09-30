package de.norm.events.scraper.delphi

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DelphiBilledTitleTest {
    @Test
    fun `bills the act the house series presents, and leaves other titles alone`() {
        // 2 October 2026; the page reads "Heute präsentiert Delphis Orakel / Stroum: 3rd live".
        delphiBilledTitle("Delphis Orakel – Stroum") shouldBe "Stroum"
        delphiBilledTitle("Schwanensee – Jenseits der Bühne") shouldBe "Schwanensee – Jenseits der Bühne"
        delphiBilledTitle("Delphis Orakel") shouldBe "Delphis Orakel"
    }
}
