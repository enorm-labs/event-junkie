package de.norm.events.importing

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class WithoutVenueAsPromoterTest {
    private fun event(vararg promoters: String) =
        ScrapedEvent(
            title = "Night",
            eventDate = LocalDate.of(2026, 10, 14),
            sourceUrl = "https://example.org/1",
            sourceId = "badehaus:1",
            promoters = promoters.toList()
        )

    @Test
    fun `drops the venue credited as its own promoter and keeps the others`() {
        event("Badehaus", "Trinity Music").withoutVenueAsPromoter("badehaus").promoters shouldBe listOf("Trinity Music")
        event("BADEHAUS GmbH").withoutVenueAsPromoter("badehaus").promoters shouldBe emptyList()
    }

    @Test
    fun `keeps a venue's name crediting a night at another venue`() {
        event("Festsaal Kreuzberg").withoutVenueAsPromoter("so36").promoters shouldBe listOf("Festsaal Kreuzberg")
    }
}
