package de.norm.events.importing

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RefusedArtistsTest {
    private fun event(vararg artists: ScrapedArtist): ScrapedEvent =
        ScrapedEvent(
            title = "A night",
            eventType = "CONCERT",
            eventDate = LocalDate.of(2026, 12, 1),
            sourceUrl = "https://example.de/night",
            sourceId = "example:night",
            artists = artists.toList()
        )

    @Test
    fun `flags a title-derived name the line-up refuses only without its closing mark`() {
        val night = event(ScrapedArtist("Special Guest!", titleDerived = true))

        strippedArtists(night).shouldBeEmpty()
        refusedArtists(night) shouldContainExactly listOf(QualityFlagKind.NON_ARTIST_NAME to "Special Guest!")
    }

    @Test
    fun `flags a refused name as billed and no billed act`() {
        val night = event(ScrapedArtist("Anna b2b TBA"), ScrapedArtist("Berta (live)"))

        strippedArtists(night).map { it.name } shouldContainExactly listOf("Anna", "Berta")
        refusedArtists(night) shouldContainExactly listOf(QualityFlagKind.NON_ARTIST_NAME to "TBA")
    }
}
